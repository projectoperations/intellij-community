// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.jetbrains.python.packaging.toolwindow

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.options.ex.SingleConfigurableEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportRawProgress
import com.jetbrains.python.PyBundle.message
import com.jetbrains.python.errorProcessing.ErrorSink
import com.jetbrains.python.errorProcessing.PyResult
import com.jetbrains.python.getOrNull
import com.jetbrains.python.onFailure
import com.jetbrains.python.onSuccess
import com.jetbrains.python.packaging.*
import com.jetbrains.python.packaging.common.PythonPackageDetails
import com.jetbrains.python.packaging.common.PythonPackageManagementListener
import com.jetbrains.python.packaging.common.PythonRepositoryPackageSpecification
import com.jetbrains.python.packaging.conda.CondaPackage
import com.jetbrains.python.packaging.management.PythonPackageInstallRequest
import com.jetbrains.python.packaging.management.PythonPackageManager
import com.jetbrains.python.packaging.management.packagesByRepository
import com.jetbrains.python.packaging.repository.PyPackageRepositories
import com.jetbrains.python.packaging.repository.PyPackageRepository
import com.jetbrains.python.packaging.repository.PyRepositoriesList
import com.jetbrains.python.packaging.repository.checkValid
import com.jetbrains.python.packaging.statistics.PythonPackagesToolwindowStatisticsCollector
import com.jetbrains.python.packaging.toolwindow.model.*
import com.jetbrains.python.sdk.PythonSdkUtil
import com.jetbrains.python.sdk.pythonSdk
import com.jetbrains.python.statistics.modules
import com.jetbrains.python.util.ShowingMessageErrorSync
import kotlinx.coroutines.*
import org.jetbrains.annotations.Nls

@Service(Service.Level.PROJECT)
class PyPackagingToolWindowService(val project: Project, val serviceScope: CoroutineScope) : Disposable {
  private var toolWindowPanel: PyPackagingToolWindowPanel? = null
  lateinit var manager: PythonPackageManager
  private var installedPackages: Map<String, InstalledPackage> = emptyMap()
  internal var currentSdk: Sdk? = null
  private var searchJob: Job? = null
  private var currentQuery: String = ""
  private val errorSink: ErrorSink = ShowingMessageErrorSync

  private val invalidRepositories: List<PyInvalidRepositoryViewData>
    get() = service<PyPackageRepositories>().invalidRepositories.map(::PyInvalidRepositoryViewData)


  fun initialize(toolWindowPanel: PyPackagingToolWindowPanel) {
    this.toolWindowPanel = toolWindowPanel
    serviceScope.launch(Dispatchers.IO) {
      service<PyPIPackageRanking>().reload()
      initForSdk(project.modules.firstOrNull()?.pythonSdk)
    }
    subscribeToChanges()
  }

  suspend fun detailsForPackage(selectedPackage: DisplayablePackage): PythonPackageDetails? = withContext(Dispatchers.IO) {
    PythonPackagesToolwindowStatisticsCollector.requestDetailsEvent.log(project)
    val spec = when (selectedPackage) {
      is InstalledPackage -> manager.findPackageSpecification(selectedPackage.name)
      is InstallablePackage -> selectedPackage.repository.findPackageSpecification(selectedPackage.name)
      is ExpandResultNode -> selectedPackage.repository.findPackageSpecification(selectedPackage.name)
    }

    if (spec == null) {
      return@withContext null
    }

    manager.repositoryManager.getPackageDetails(spec).onFailure { errorSink.emit(it) }.getOrNull()
  }


  fun handleSearch(query: String) {
    val prevSelected = toolWindowPanel?.getSelectedPackage()

    currentQuery = query
    if (query.isNotEmpty()) {
      searchJob?.cancel()
      searchJob = serviceScope.launch {
        val installed = installedPackages.values.filter { pkg ->
          when {
            pkg.instance is CondaPackage && !pkg.instance.installedWithPip -> StringUtil.containsIgnoreCase(pkg.name, query)
            else -> StringUtil.containsIgnoreCase(normalizePackageName(pkg.name), normalizePackageName(query))
          }
        }

        val packagesFromRepos = manager.repositoryManager.searchPackages(query).map {
          sortPackagesForRepo(it.value, query, it.key)
        }.toList()

        if (isActive) {
          withContext(Dispatchers.Main) {
            toolWindowPanel?.showSearchResult(installed, packagesFromRepos + invalidRepositories)
            prevSelected?.name?.let { toolWindowPanel?.selectPackageName(it) }
          }
        }
      }
    }
    else {
      val packagesByRepository = manager.repositoryManager.packagesByRepository().map { (repository, packages) ->
        val shownPackages = packages.asSequence().limitResultAndFilterOutInstalled(repository)
        PyPackagesViewData(repository, shownPackages, moreItems = packages.size - PACKAGES_LIMIT)
      }.toList()

      toolWindowPanel?.resetSearch(installedPackages.values.toList(), packagesByRepository + invalidRepositories, currentSdk)
      prevSelected?.name?.let { toolWindowPanel?.selectPackageName(it) }
    }
  }

  suspend fun installPackage(installRequest: PythonPackageInstallRequest, options: List<String> = emptyList()) {
    PythonPackagesToolwindowStatisticsCollector.installPackageEvent.log(project)
    val result = manager.installPackage(installRequest, options)
    result.handleActionCompleted(message("python.packaging.notification.installed", installRequest.title))
  }

  suspend fun deletePackage(vararg selectedPackages: InstalledPackage) {
    PythonPackagesToolwindowStatisticsCollector.uninstallPackageEvent.log(project)
    val result = manager.uninstallPackage(*selectedPackages.map { it.instance.name }.toTypedArray())
    result.handleActionCompleted(message("python.packaging.notification.deleted", selectedPackages.joinToString(", ") { it.name }))
  }

  suspend fun updatePackage(vararg specifications: PythonRepositoryPackageSpecification) {
    val result = manager.updatePackages(*specifications)

    val singlePackage = specifications.singleOrNull()
    val title = if (singlePackage != null) {
      val version = singlePackage.versionSpec?.version
      message("python.packaging.notification.updated", singlePackage.name, version)
    }
    else message("python.packaging.notification.all.updated")

    result.handleActionCompleted(title)
  }

  internal suspend fun initForSdk(sdk: Sdk?) {
    if (sdk == null) {
      toolWindowPanel?.packageListController?.setLoadingState(false)
    }

    if (sdk == currentSdk) {
      return
    }

    withContext(Dispatchers.EDT) {
      toolWindowPanel?.startLoadingSdk()
    }

    val previousSdk = currentSdk
    currentSdk = sdk
    if (sdk == null) {
      return
    }

    manager = PythonPackageManager.forSdk(project, sdk)
    withContext(Dispatchers.EDT) {
      toolWindowPanel?.contentVisible = currentSdk != null
      if (currentSdk == null || currentSdk != previousSdk) {
        toolWindowPanel?.setEmpty()
      }
    }
  }

  private fun subscribeToChanges() {
    val connection = project.messageBus.connect(this)
    connection.subscribe(PythonPackageManager.PACKAGE_MANAGEMENT_TOPIC, object : PythonPackageManagementListener {
      override fun packagesChanged(sdk: Sdk) {
        if (currentSdk == sdk) serviceScope.launch(Dispatchers.Main) {
          refreshInstalledPackages()
        }
      }

      override fun outdatedPackagesChanged(sdk: Sdk) {
        if (currentSdk == sdk) serviceScope.launch(Dispatchers.Main) {
          refreshInstalledPackages()
        }

      }
    })
    connection.subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
      override fun rootsChanged(event: ModuleRootEvent) {
        serviceScope.launch(Dispatchers.IO) {
          initForSdk(project.modules.firstOrNull()?.pythonSdk)
        }
      }
    })

    connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
      override fun selectionChanged(event: FileEditorManagerEvent) {
        event.newFile?.let { newFile ->
          serviceScope.launch {
            val sdk = readAction {
              val module = ModuleUtilCore.findModuleForFile(newFile, project)
              PythonSdkUtil.findPythonSdk(module)
            } ?: return@launch
            initForSdk(sdk)
          }
        }
      }
    })
  }

  suspend fun refreshInstalledPackages() {
    val packages = manager.installedPackages.map {
      val spec = manager.findPackageSpecification(it.name, it.version)
      val repository = spec?.repository
      val nextVersionRaw = manager.outdatedPackages[it.name]?.latestVersion
      val nextVersion = nextVersionRaw?.let { PyPackageVersionNormalizer.normalize(it) }
      InstalledPackage(it, repository, nextVersion)
    }

    installedPackages = packages.associateBy { it.name }

    withContext(Dispatchers.Main) {
      handleSearch(query = currentQuery)
    }
  }

  private suspend fun PyResult<*>.handleActionCompleted(text: @Nls String) = this
    .onSuccess {
      VirtualFileManager.getInstance().asyncRefresh()
      showPackagingNotification(text)
    }
    .onFailure {
      errorSink.emit(it)
    }

  private suspend fun showPackagingNotification(text: @Nls String) {
    val notification = NotificationGroupManager.getInstance()
      .getNotificationGroup("PythonPackages")
      .createNotification(text, NotificationType.INFORMATION)

    withContext(Dispatchers.Main) {
      notification.notify(project)
    }
  }

  private fun sortPackagesForRepo(
    packageNames: List<String>,
    query: String,
    repository: PyPackageRepository,
    skipItems: Int = 0,
  ): PyPackagesViewData {

    val comparator = createNameComparator(query, repository.repositoryUrl ?: "")

    val shownPackages = packageNames.asSequence()
      .sortedWith(comparator)
      .limitResultAndFilterOutInstalled(repository, skipItems)
    val exactMatch = shownPackages.indexOfFirst { StringUtil.equalsIgnoreCase(it.name, query) }
    val moreItems = (packageNames.size - (skipItems + PACKAGES_LIMIT)).takeIf { it > 0 } ?: 0
    return PyPackagesViewData(repository, shownPackages, exactMatch, moreItems)
  }


  override fun dispose() {
    searchJob?.cancel()
    serviceScope.cancel()
  }

  fun reloadPackages() {
    serviceScope.launch(Dispatchers.IO) {
      withBackgroundProgress(project, message("python.packaging.loading.packages.progress.text"), cancellable = false) {
        reportRawProgress {
          manager.reloadPackages()
          refreshInstalledPackages()
          manager.repositoryManager.refreshCaches()
        }
      }
    }
  }

  fun manageRepositories() {
    val updated = SingleConfigurableEditor(project, PyRepositoriesList(project)).showAndGet()
    if (updated) {
      PythonPackagesToolwindowStatisticsCollector.repositoriesChangedEvent.log(project)
      serviceScope.launch(Dispatchers.IO) {
        val packageService = PyPackageService.getInstance()
        val repositoryService = service<PyPackageRepositories>()
        val allRepos = repositoryService.repositories.map { it.repositoryUrl }
        packageService.additionalRepositories.asSequence()
          .filter { it !in allRepos }
          .forEach { packageService.removeRepository(it) }

        val (valid, invalid) = repositoryService.repositories.partition { it.checkValid() }
        repositoryService.invalidRepositories.clear()
        repositoryService.invalidRepositories.addAll(invalid)
        invalid.forEach { packageService.removeRepository(it.repositoryUrl!!) }

        valid.asSequence()
          .map { it.repositoryUrl }
          .filter { it !in packageService.additionalRepositories }
          .forEach { packageService.addRepository(it) }

        reloadPackages()
      }
    }
  }

  fun getMoreResultsForRepo(repository: PyPackageRepository, skipItems: Int): PyPackagesViewData {
    if (currentQuery.isNotEmpty()) {
      return sortPackagesForRepo(manager.repositoryManager.searchPackages(currentQuery, repository), currentQuery, repository, skipItems)
    }
    else {
      val packagesFromRepo = repository.getPackages()
      val page = packagesFromRepo.asSequence().limitResultAndFilterOutInstalled(repository, skipItems)
      return PyPackagesViewData(repository, page, moreItems = packagesFromRepo.size - (PACKAGES_LIMIT + skipItems))
    }
  }

  private fun Sequence<String>.limitResultAndFilterOutInstalled(repository: PyPackageRepository, skipItems: Int = 0): List<DisplayablePackage> {
    return drop(skipItems)
      .take(PACKAGES_LIMIT)
      .filter { pkg -> installedPackages.values.find { it.name.lowercase() == pkg.lowercase() } == null }
      .map { pkg -> InstallablePackage(pkg, repository) }
      .toList()
  }

  companion object {
    private const val PACKAGES_LIMIT = 50

    fun getInstance(project: Project): PyPackagingToolWindowService = project.service<PyPackagingToolWindowService>()

    private fun createNameComparator(query: String, url: String): Comparator<String> {
      val nameComparator = Comparator<String> { name1, name2 ->
        val queryLowerCase = query.lowercase()
        return@Comparator when {
          name1.startsWith(queryLowerCase) && name2.startsWith(queryLowerCase) -> name1.length - name2.length
          name1.startsWith(queryLowerCase) -> -1
          name2.startsWith(queryLowerCase) -> 1
          else -> name1.compareTo(name2)
        }
      }

      if (PyPIPackageUtil.isPyPIRepository(url)) {
        val ranking = service<PyPIPackageRanking>().packageRank
        return Comparator { p1, p2 ->
          val rank1 = ranking[p1.lowercase()]
          val rank2 = ranking[p2.lowercase()]
          return@Comparator when {
            rank1 != null && rank2 == null -> -1
            rank1 == null && rank2 != null -> 1
            rank1 != null && rank2 != null -> rank2 - rank1
            else -> nameComparator.compare(p1, p2)
          }
        }
      }

      return nameComparator
    }
  }
}