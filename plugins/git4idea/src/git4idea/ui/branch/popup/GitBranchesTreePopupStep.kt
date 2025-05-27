// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.ui.branch.popup

import com.intellij.dvcs.DvcsUtil
import com.intellij.dvcs.getCommonName
import com.intellij.dvcs.ui.DvcsBundle
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.PresentationFactory
import com.intellij.openapi.actionSystem.impl.Utils
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopupStep
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.PopupStep.FINAL_CHOICE
import com.intellij.ui.ExperimentalUI
import com.intellij.ui.popup.ActionPopupOptions
import com.intellij.ui.popup.ActionPopupStep
import com.intellij.ui.popup.PopupFactoryImpl
import com.intellij.vcs.git.shared.actions.GitDataKeys
import com.intellij.vcs.git.shared.repo.GitRepositoriesFrontendHolder
import git4idea.GitReference
import git4idea.GitVcs
import git4idea.actions.branch.GitBranchActionsDataKeys
import git4idea.config.GitVcsSettings
import git4idea.repo.GitRefUtil
import git4idea.repo.GitRepository
import git4idea.ui.branch.GIT_SINGLE_REF_ACTION_GROUP
import git4idea.ui.branch.GitBranchPopupActions.EXPERIMENTAL_BRANCH_POPUP_ACTION_GROUP
import git4idea.ui.branch.popup.GitBranchesTreePopupBase.Companion.TOP_LEVEL_ACTION_PLACE
import git4idea.ui.branch.tree.*
import org.intellij.lang.annotations.Language
import javax.swing.JComponent

internal class GitBranchesTreePopupStep(
  project: Project,
  selectedRepository: GitRepository?,
  repositories: List<GitRepository>,
  private val isFirstStep: Boolean,
) : GitBranchesTreePopupStepBase(project, selectedRepository, repositories) {
  private var finalRunnable: Runnable? = null

  private val topLevelItems: List<Any> = buildList {
    val presentationFactory = PresentationFactory()

    if (ExperimentalUI.isNewUI() && isFirstStep) {
      val experimentalUIActionsGroup = ActionManager.getInstance().getAction(EXPERIMENTAL_BRANCH_POPUP_ACTION_GROUP) as? ActionGroup
      if (experimentalUIActionsGroup != null) {
        addAll(createTopLevelActionItems(project, experimentalUIActionsGroup, presentationFactory, selectedRepository, affectedRepositories).addSeparators())
        if (this.isNotEmpty()) {
          add(GitBranchesTreePopupBase.createTreeSeparator())
        }
      }
    }
    val actionGroup = ActionManager.getInstance().getAction(TOP_LEVEL_ACTION_GROUP) as? ActionGroup
    if (actionGroup != null) {
      // get selected repo inside actions
      addAll(createTopLevelActionItems(project, actionGroup, presentationFactory, selectedRepository, affectedRepositories).addSeparators())
      if (this.isNotEmpty()) {
        add(GitBranchesTreePopupBase.createTreeSeparator())
      }
    }
  }

  override var treeModel: GitBranchesTreeModel = createTreeModel(false)
    private set

  override fun createTreeModel(filterActive: Boolean): GitBranchesTreeModel {
    val holder = GitRepositoriesFrontendHolder.getInstance(project)
    val repositoriesFrontendModel = repositories.map { holder.get(it.rpcId) }

    val model = when {
      !filterActive && repositories.size > 1 && !GitVcsSettings.getInstance(project).shouldExecuteOperationsOnAllRoots() && selectedRepository != null -> {
        GitBranchesTreeSelectedRepoModel(project, holder.get(selectedRepository.rpcId), repositoriesFrontendModel, topLevelItems)
      }
      filterActive && repositories.size > 1 -> {
        GitBranchesTreeMultiRepoFilteringModel(project, repositoriesFrontendModel, topLevelItems)
      }
      !filterActive && repositories.size > 1 -> GitBranchesTreeMultiRepoModel(project, repositoriesFrontendModel, topLevelItems)
      else -> GitBranchesTreeSingleRepoModel(project, repositoriesFrontendModel.first(), topLevelItems)
    }
    return model.apply(GitBranchesTreeModel::init)
  }

  override fun setTreeModel(treeModel: GitBranchesTreeModel) {
    this.treeModel = treeModel
  }

  override fun getFinalRunnable() = finalRunnable

  override fun onChosen(selectedValue: Any?, finalChoice: Boolean): PopupStep<out Any>? {
    if (selectedValue is GitBranchesTreeModel.RepositoryNode) {
      val repo = repositories.find { it.rpcId == selectedValue.repository.repositoryId } ?: return null
      return GitBranchesTreePopupStep(project, repo, listOf(repo), false)
    }

    val refUnderRepository = selectedValue as? GitBranchesTreeModel.RefUnderRepository
    val reference = selectedValue as? GitReference ?: refUnderRepository?.ref

    if (reference != null) {
      val actionGroup = ActionManager.getInstance().getAction(GIT_SINGLE_REF_ACTION_GROUP) as? ActionGroup ?: DefaultActionGroup()
      val repo = refUnderRepository?.repository?.let { refRepo -> repositories.find { it.rpcId == refRepo.repositoryId } }
      return createActionStep(actionGroup, project, selectedRepository, repo?.let(::listOf) ?: affectedRepositories, reference)
    }

    if (selectedValue is PopupFactoryImpl.ActionItem) {
      if (!selectedValue.isEnabled) return FINAL_CHOICE
      val action = selectedValue.action
      if (action is ActionGroup && (!finalChoice || !selectedValue.isPerformGroup)) {
        return createActionStep(action, project, selectedRepository, affectedRepositories)
      }
      else {
        finalRunnable = Runnable {
          val place = if (isFirstStep) TOP_LEVEL_ACTION_PLACE else SINGLE_REPOSITORY_ACTION_PLACE
          val dataContext = createDataContext(project, null, selectedRepository, affectedRepositories)
          ActionUtil.invokeAction(action, dataContext, place, null, null)
        }
      }
    }

    return FINAL_CHOICE
  }

  override fun getTitle(): String? =
    when {
      ExperimentalUI.isNewUI() -> null
      !isFirstStep -> null
      repositories.size > 1 -> DvcsBundle.message("branch.popup.vcs.name.branches", GitVcs.DISPLAY_NAME.get())
      else -> repositories.single().let {
        DvcsBundle.message("branch.popup.vcs.name.branches.in.repo", it.vcs.displayName, DvcsUtil.getShortRepositoryName(it))
      }
    }

  override fun shouldValidateNotNullTreeModel(): Boolean = !isFirstStep || super.shouldValidateNotNullTreeModel()

  fun isBranchesDiverged(): Boolean {
    return repositories.size > 1
           && getCommonName(repositories) { GitRefUtil.getCurrentReference(it)?.fullName ?: return@getCommonName null } == null
           && GitVcsSettings.getInstance(project).shouldExecuteOperationsOnAllRoots()
  }

  companion object {
    @Language("devkit-action-id")
    private const val TOP_LEVEL_ACTION_GROUP = "Git.Branches.List"
    @Language("devkit-action-id")
    private const val BRANCH_ACTION_GROUP = "Git.Branch"

    internal val SINGLE_REPOSITORY_ACTION_PLACE = ActionPlaces.getPopupPlace("GitBranchesPopup.SingleRepo.Branch.Actions")

    private fun createTopLevelActionItems(project: Project,
                                          actionGroup: ActionGroup,
                                          presentationFactory: PresentationFactory,
                                          selectedRepository: GitRepository?,
                                          repositories: List<GitRepository>): List<PopupFactoryImpl.ActionItem> {
      val dataContext = createDataContext(project, null, selectedRepository, repositories)
      val actionItems = ActionPopupStep.createActionItems(
        actionGroup, dataContext, TOP_LEVEL_ACTION_PLACE, presentationFactory,
        ActionPopupOptions.showDisabled())

      if (actionItems.singleOrNull()?.action == Utils.EMPTY_MENU_FILLER) {
        return emptyList()
      }

      return actionItems
    }

    private fun createActionStep(actionGroup: ActionGroup,
                                 project: Project,
                                 selectedRepository: GitRepository?,
                                 repositories: List<GitRepository>,
                                 reference: GitReference? = null): ListPopupStep<*> {
      val dataContext = createDataContext(project, null, selectedRepository, repositories, reference)
      return JBPopupFactory.getInstance()
        .createActionsStep(actionGroup, dataContext, SINGLE_REPOSITORY_ACTION_PLACE, false, true, null, null, false, 0, false)
    }

    private fun List<PopupFactoryImpl.ActionItem>.addSeparators(): List<Any> {
      val actionsWithSeparators = mutableListOf<Any>()
      for (action in this) {
        if (action.isPrependWithSeparator) {
          actionsWithSeparators.add(GitBranchesTreePopupBase.createTreeSeparator(action.separatorText))
        }
        actionsWithSeparators.add(action)
      }
      return actionsWithSeparators
    }

    internal fun createDataContext(project: Project,
                                   component: JComponent?,
                                   selectedRepository: GitRepository?,
                                   repositories: List<GitRepository>,
                                   reference: GitReference? = null): DataContext =
      CustomizedDataContext.withSnapshot(
        DataManager.getInstance().getDataContext(component)) { sink ->
        sink[CommonDataKeys.PROJECT] = project
        sink[GitBranchActionsDataKeys.AFFECTED_REPOSITORIES] = repositories
        sink[GitBranchActionsDataKeys.SELECTED_REPOSITORY] = selectedRepository
        sink[GitDataKeys.SELECTED_REF] = reference
      }
  }
}