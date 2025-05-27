// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.shared.ref

import com.intellij.vcs.git.shared.repo.GitRepositoryFrontendModel
import git4idea.GitBranch
import git4idea.GitStandardLocalBranch
import git4idea.GitStandardRemoteBranch
import git4idea.GitTag
import org.jetbrains.annotations.NonNls

object GitRefUtil {
  private val knownPrefixes = listOf(GitBranch.REFS_HEADS_PREFIX, GitBranch.REFS_REMOTES_PREFIX, GitTag.REFS_TAGS_PREFIX)

  @JvmStatic
  fun stripRefsPrefix(@NonNls refName: String): @NonNls String {
    for (prefix in knownPrefixes) {
      if (refName.startsWith(prefix)) return refName.substring(prefix.length)
    }

    return refName
  }

  fun getCommonCurrentBranch(repositories: Collection<GitRepositoryFrontendModel>): GitStandardLocalBranch? =
    repositories.map { it.state.currentBranch }.distinct().singleOrNull()

  fun getCommonLocalBranches(repositories: Collection<GitRepositoryFrontendModel>): Collection<GitStandardLocalBranch> {
    return findCommon(repositories.asSequence().map { repository -> repository.state.refs.localBranches })
  }

  fun getCommonRemoteBranches(repositories: Collection<GitRepositoryFrontendModel>): Collection<GitStandardRemoteBranch> {
    return findCommon(repositories.asSequence().map { repository -> repository.state.refs.remoteBranches })
  }

  fun getCommonTags(repositories: Collection<GitRepositoryFrontendModel>): Collection<GitTag> {
    return findCommon(repositories.asSequence().map { repository -> repository.state.refs.tags })
  }

  private fun <T> findCommon(collections: Sequence<Collection<T>>): Collection<T> =
    if (collections.none()) emptyList() else collections.reduce { acc, set -> acc.intersect(set) }
}