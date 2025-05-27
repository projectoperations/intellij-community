// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.intellij.xdebugger.impl.hotswap

import com.intellij.mock.MockVirtualFile
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.HeavyPlatformTestCase
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import org.junit.jupiter.api.Assertions.assertTrue

class HotSwapSessionManagerTest : HeavyPlatformTestCase() {
  fun testCurrentSession() {
    val manager = HotSwapSessionManager.getInstance(project)
    val disposable = Disposer.newDisposable(testRootDisposable)
    assertNull(manager.currentSession)
    val hotSwapSession = manager.createSession(MockHotSwapProvider(), disposable)
    assertSame(hotSwapSession, manager.currentSession)
    Disposer.dispose(disposable)
    assertNull(manager.currentSession)
  }

  fun testListenerAfterSessionStart(): Unit = runBlocking {
    val manager = HotSwapSessionManager.getInstance(project)
    val disposable1 = Disposer.newDisposable(testRootDisposable)
    val disposable2 = Disposer.newDisposable(testRootDisposable)
    manager.createSession(MockHotSwapProvider(), disposable1)
    val channel = addStatusListener(disposable2)
    assertEquals(HotSwapVisibleStatus.NO_CHANGES, channel.receive())
    Disposer.dispose(disposable1)
    assertCompletedAndDispose(channel, disposable2)
  }

  fun testOnChanges() = runBlocking {
    val manager = HotSwapSessionManager.getInstance(project)
    val disposable1 = Disposer.newDisposable(testRootDisposable)
    val disposable2 = Disposer.newDisposable(testRootDisposable)
    val channel = addStatusListener(disposable2)
    assertNull(channel.receive())
    val provider = MockHotSwapProvider()
    val hotSwapSession = manager.createSession(provider, disposable1)
    assertEquals(HotSwapVisibleStatus.NO_CHANGES, channel.receive())
    assertEmpty(hotSwapSession.getChanges())

    provider.collector.addFile(MockVirtualFile("a.txt"))
    assertEquals(HotSwapVisibleStatus.CHANGES_READY, channel.receive())
    assertEquals(1, hotSwapSession.getChanges().size)

    provider.collector.addFile(MockVirtualFile("b.txt"))
    assertTrue(channel.isEmpty)
    assertEquals(2, hotSwapSession.getChanges().size)
    Disposer.dispose(disposable1)
    assertCompletedAndDispose(channel, disposable2)
  }

  fun testHotSwap() = runBlocking {
    val manager = HotSwapSessionManager.getInstance(project)
    val disposable1 = Disposer.newDisposable(testRootDisposable)
    val disposable2 = Disposer.newDisposable(testRootDisposable)
    val channel = addStatusListener(disposable2)
    assertNull(channel.receive())
    val provider = MockHotSwapProvider()
    val hotSwapSession = manager.createSession(provider, disposable1)
    assertEquals(HotSwapVisibleStatus.NO_CHANGES, channel.receive())

    run {
      provider.collector.addFile(MockVirtualFile("a.txt"))
      assertEquals(HotSwapVisibleStatus.CHANGES_READY, channel.receive())
      val listener = provider.doHotSwap(hotSwapSession)
      assertEquals(HotSwapVisibleStatus.IN_PROGRESS, channel.receive())
      listener.onFinish()
      assertEquals(HotSwapVisibleStatus.NO_CHANGES, channel.receive())
      assertEmpty(hotSwapSession.getChanges())
    }

    run {
      provider.collector.addFile(MockVirtualFile("a.txt"))
      assertEquals(HotSwapVisibleStatus.CHANGES_READY, channel.receive())
      val listener = provider.doHotSwap(hotSwapSession)
      assertEquals(HotSwapVisibleStatus.IN_PROGRESS, channel.receive())
      listener.onSuccessfulReload()
      assertEquals(HotSwapVisibleStatus.SUCCESS, channel.receive())
      assertEmpty(hotSwapSession.getChanges())
    }

    run {
      provider.collector.addFile(MockVirtualFile("a.txt"))
      assertEquals(HotSwapVisibleStatus.CHANGES_READY, channel.receive())
      val listener = provider.doHotSwap(hotSwapSession)
      assertEquals(HotSwapVisibleStatus.IN_PROGRESS, channel.receive())
      listener.onCanceled()
      assertEquals(HotSwapVisibleStatus.CHANGES_READY, channel.receive())
      assertEquals(1, hotSwapSession.getChanges().size)
    }

    run {
      assertEquals(1, hotSwapSession.getChanges().size)
      assertTrue(channel.isEmpty)
      val listener = provider.doHotSwap(hotSwapSession)
      assertEquals(HotSwapVisibleStatus.IN_PROGRESS, channel.receive())
      listener.onFailure()
      assertEquals(HotSwapVisibleStatus.NO_CHANGES, channel.receive())
      assertEquals(1, hotSwapSession.getChanges().size)
    }
    Disposer.dispose(disposable1)
    assertCompletedAndDispose(channel, disposable2)
  }

  fun testSameHotSwapStatusHasNoEffect() = runBlocking {
    val manager = HotSwapSessionManager.getInstance(project)
    val disposable0 = Disposer.newDisposable(testRootDisposable)
    val disposable1 = Disposer.newDisposable(testRootDisposable)
    val channel = addStatusListener(disposable0)
    assertNull(channel.receive())

    val provider = MockHotSwapProvider()
    val hotSwapSession = manager.createSession(provider, disposable1)
    assertEquals(HotSwapVisibleStatus.NO_CHANGES, channel.receive())

    provider.collector.addFile(MockVirtualFile("a.txt"))
    assertEquals(HotSwapVisibleStatus.CHANGES_READY, channel.receive())

    val listener = hotSwapSession.startHotSwapListening()
    assertEquals(HotSwapVisibleStatus.IN_PROGRESS, channel.receive())

    listener.onCanceled()
    assertEquals(HotSwapVisibleStatus.CHANGES_READY, channel.receive())

    listener.onCanceled()
    assertTrue(channel.isEmpty)

    Disposer.dispose(disposable1)
    assertCompleted(channel)

    listener.onCanceled()
    assertTrue(channel.isEmpty)
    Disposer.dispose(disposable0)
  }

  fun testAddingListenerConcurrentlyToSessionClose() {
    repeat(100) {
      runBlocking {
        val manager = HotSwapSessionManager.getInstance(project)
        val channel = Channel<HotSwapVisibleStatus?>()
        val disposable1 = Disposer.newDisposable(testRootDisposable)
        val disposable2 = Disposer.newDisposable(testRootDisposable)
        manager.createSession(MockHotSwapProvider(), disposable1)
        val closeOldAndAddFile = launch(Dispatchers.Default) {
          Disposer.dispose(disposable1)
          val provider = MockHotSwapProvider()
          manager.createSession(provider, disposable2)
          provider.collector.addFile(MockVirtualFile("a.txt"))
        }
        val scope = this
        val addListener = launch(Dispatchers.Default) {
          scope.addStatusListener(disposable2, channel)
        }
        closeOldAndAddFile.join()
        addListener.join()
        var status = channel.receive()
        if (status == HotSwapVisibleStatus.SESSION_COMPLETED) {
          status = channel.receive()
        }
        if (status == null) {
          status = channel.receive()
        }
        if (status == HotSwapVisibleStatus.NO_CHANGES) {
          status = channel.receive()
        }
        assertEquals(HotSwapVisibleStatus.CHANGES_READY, status)
        Disposer.dispose(disposable2)
      }
    }
  }

  fun testMultipleSessionsNotifyLastOneWhenNoSelected() = runBlocking {
    val disposable0 = Disposer.newDisposable(testRootDisposable)
    val disposable1 = Disposer.newDisposable(testRootDisposable)
    val disposable2 = Disposer.newDisposable(testRootDisposable)

    val manager = HotSwapSessionManager.getInstance(project)
    val provider1 = MockHotSwapProvider()
    val session1 = manager.createSession(provider1, disposable1)
    val provider2 = MockHotSwapProvider()
    val session2 = manager.createSession(provider2, disposable2)

    val channel = addSessionAndStatusListener(disposable0)

    assertSame(session2, manager.currentSession)
    assertEquals(session2 to HotSwapVisibleStatus.NO_CHANGES, channel.receive())

    provider1.collector.addFile(MockVirtualFile("a.txt"))
    assertTrue(channel.isEmpty)

    provider2.collector.addFile(MockVirtualFile("a.txt"))
    assertEquals(session2 to HotSwapVisibleStatus.CHANGES_READY, channel.receive())

    Disposer.dispose(disposable2)
    var currentStatus = channel.receive()
    val expectedNextStatus = session1 to HotSwapVisibleStatus.CHANGES_READY
    // session complete status might be skipped
    if (currentStatus == session2 to HotSwapVisibleStatus.SESSION_COMPLETED) {
      currentStatus = channel.receive()
    }
    // null status might be skipped
    if (currentStatus == null to null) {
      currentStatus = channel.receive()
    }
    assertEquals(expectedNextStatus, currentStatus)

    Disposer.dispose(disposable1)
    assertCompleted(channel, session1)

    Disposer.dispose(disposable0)
  }

  fun testCloseNonSelectedSession() = runBlocking {
    val disposable0 = Disposer.newDisposable(testRootDisposable)
    val disposable1 = Disposer.newDisposable(testRootDisposable)
    val disposable2 = Disposer.newDisposable(testRootDisposable)

    val manager = HotSwapSessionManager.getInstance(project)
    val provider1 = MockHotSwapProvider()
    manager.createSession(provider1, disposable1)
    val provider2 = MockHotSwapProvider()
    val session2 = manager.createSession(provider2, disposable2)

    val channel = addSessionAndStatusListener(disposable0)

    assertSame(session2, manager.currentSession)
    assertEquals(session2 to HotSwapVisibleStatus.NO_CHANGES, channel.receive())

    provider1.collector.addFile(MockVirtualFile("a.txt"))
    assertTrue(channel.isEmpty)

    provider2.collector.addFile(MockVirtualFile("a.txt"))
    assertEquals(session2 to HotSwapVisibleStatus.CHANGES_READY, channel.receive())

    Disposer.dispose(disposable1)
    assertTrue(channel.isEmpty)

    Disposer.dispose(disposable2)
    assertCompleted(channel, session2)

    Disposer.dispose(disposable0)
  }

  fun testSessionSelection() = runBlocking {
    val disposable0 = Disposer.newDisposable(testRootDisposable)
    val disposable1 = Disposer.newDisposable(testRootDisposable)
    val disposable2 = Disposer.newDisposable(testRootDisposable)

    val manager = HotSwapSessionManager.getInstance(project)
    val provider1 = MockHotSwapProvider()
    val session1 = manager.createSession(provider1, disposable1)
    val provider2 = MockHotSwapProvider()
    val session2 = manager.createSession(provider2, disposable2)

    val channel = addSessionAndStatusListener(disposable0)

    assertSame(session2, manager.currentSession)
    assertEquals(session2 to HotSwapVisibleStatus.NO_CHANGES, channel.receive())

    manager.onSessionSelected(session1)
    assertSame(session1, manager.currentSession)
    assertEquals(session1 to HotSwapVisibleStatus.NO_CHANGES, channel.receive())

    provider1.collector.addFile(MockVirtualFile("a.txt"))
    assertEquals(session1 to HotSwapVisibleStatus.CHANGES_READY, channel.receive())

    manager.onSessionSelected(session2)
    assertSame(session2, manager.currentSession)
    assertEquals(session2 to HotSwapVisibleStatus.NO_CHANGES, channel.receive())

    provider2.collector.addFile(MockVirtualFile("a.txt"))
    assertEquals(session2 to HotSwapVisibleStatus.CHANGES_READY, channel.receive())

    Disposer.dispose(disposable2)
    var currentStatus = channel.receive()
    val expectedNextStatus = session1 to HotSwapVisibleStatus.CHANGES_READY
    // session complete status might be skipped
    if (currentStatus == session2 to HotSwapVisibleStatus.SESSION_COMPLETED) {
      currentStatus = channel.receive()
    }
    // null status might be skipped
    if (currentStatus == null to null) {
      currentStatus = channel.receive()
    }
    assertEquals(expectedNextStatus, currentStatus)

    Disposer.dispose(disposable1)
    assertCompleted(channel, session1)

    Disposer.dispose(disposable0)
  }

  fun testSessionIsNotLeakedAfterClose() = runBlocking {
    val disposable = Disposer.newDisposable(testRootDisposable)

    val manager = HotSwapSessionManager.getInstance(project)
    val provider = MockHotSwapProvider()
    val session = manager.createSession(provider, disposable)

    val listenerDisposable = Disposer.newDisposable(testRootDisposable)
    val channel = addSessionAndStatusListener(listenerDisposable)

    assertEquals(session to HotSwapVisibleStatus.NO_CHANGES, channel.receive())
    Disposer.dispose(disposable)

    assertCompleted(channel, session)
    Disposer.dispose(listenerDisposable)
  }

  fun testSelectionAlreadySelectedOrClosedHasNoEffect() = runBlocking {
    val disposable0 = Disposer.newDisposable(testRootDisposable)
    val disposable1 = Disposer.newDisposable(testRootDisposable)

    val manager = HotSwapSessionManager.getInstance(project)
    val provider1 = MockHotSwapProvider()
    val session1 = manager.createSession(provider1, disposable1)

    val channel = addStatusListener(disposable0)

    assertSame(session1, manager.currentSession)
    assertEquals(HotSwapVisibleStatus.NO_CHANGES, channel.receive())

    manager.onSessionSelected(session1)
    assertSame(session1, manager.currentSession)
    assertTrue(channel.isEmpty)

    manager.onSessionSelected(session1)
    assertSame(session1, manager.currentSession)
    assertTrue(channel.isEmpty)

    Disposer.dispose(disposable1)
    assertNull(manager.currentSession)
    assertCompleted(channel)

    manager.onSessionSelected(session1)
    assertNull(manager.currentSession)
    assertTrue(channel.isEmpty)

    Disposer.dispose(disposable0)
  }

  fun testHidingAndAddingChangesAfterIt() = runBlocking {
    val disposable = Disposer.newDisposable(testRootDisposable)

    val manager = HotSwapSessionManager.getInstance(project)
    val provider = MockHotSwapProvider()
    manager.createSession(provider, disposable)

    val listenerDisposable = Disposer.newDisposable(testRootDisposable)
    val channel = addStatusListener(listenerDisposable)
    assertEquals(HotSwapVisibleStatus.NO_CHANGES, channel.receive())

    provider.collector.addFile(MockVirtualFile("a.txt"))
    assertEquals(HotSwapVisibleStatus.CHANGES_READY, channel.receive())

    manager.hide()
    assertEquals(HotSwapVisibleStatus.HIDDEN, channel.receive())

    provider.collector.addFile(MockVirtualFile("a.txt"))
    assertEquals(HotSwapVisibleStatus.CHANGES_READY, channel.receive())

    Disposer.dispose(disposable)

    assertCompleted(channel)
    Disposer.dispose(listenerDisposable)
  }

  private suspend fun assertCompletedAndDispose(
    channel: ReceiveChannel<HotSwapVisibleStatus?>,
    disposable2: Disposable,
  ) {
    assertCompleted(channel)
    Disposer.dispose(disposable2)
    assertTrue(channel.isEmpty) { "Expected no more events, but got ${channel.tryReceive().getOrNull()}" }
  }

  private suspend fun assertCompleted(channel: ReceiveChannel<HotSwapVisibleStatus?>) {
    var status = channel.receive()
    if (status == HotSwapVisibleStatus.SESSION_COMPLETED) {
      status = channel.receive()
    }
    assertNull(status)
  }

  private suspend fun assertCompleted(
    channel: ReceiveChannel<Pair<HotSwapSession<*>?, HotSwapVisibleStatus?>>,
    session: HotSwapSession<MockVirtualFile>,
  ) {
    var status = channel.receive()
    if (status == (session to HotSwapVisibleStatus.SESSION_COMPLETED)) {
      status = channel.receive()
    }
    assertEquals(null to null, status)
  }

  private fun <T> CoroutineScope.addStatusListener(disposable: Disposable, channel: SendChannel<T>, selector: (CurrentSessionState?) -> T) {
    launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
      val job = coroutineContext.job
      Disposer.register(disposable) { job.cancel() }
      try {
        HotSwapSessionManager.getInstance(this@HotSwapSessionManagerTest.project).currentStatusFlow.collect { status ->
          channel.send(selector(status))
        }
      }
      catch (e: CancellationException) {
        channel.close(e)
        throw e
      }
    }
  }

  private fun CoroutineScope.addStatusListener(disposable: Disposable): ReceiveChannel<HotSwapVisibleStatus?> {
    val channel = Channel<HotSwapVisibleStatus?>()
    addStatusListener(disposable, channel)
    return channel
  }

  private fun CoroutineScope.addStatusListener(disposable: Disposable, channel: SendChannel<HotSwapVisibleStatus?>) {
    addStatusListener(disposable, channel) { it?.status }
  }

  private fun CoroutineScope.addSessionAndStatusListener(disposable: Disposable): ReceiveChannel<Pair<HotSwapSession<*>?, HotSwapVisibleStatus?>> {
    val channel = Channel<Pair<HotSwapSession<*>?, HotSwapVisibleStatus?>>()
    addStatusListener(disposable, channel) { it?.session to it?.status }
    return channel
  }
}

internal class MockHotSwapProvider : HotSwapProvider<MockVirtualFile> {
  lateinit var collector: MockChangesCollector

  override fun createChangesCollector(
    session: HotSwapSession<MockVirtualFile>,
    coroutineScope: CoroutineScope,
    listener: SourceFileChangesListener,
  ): SourceFileChangesCollector<MockVirtualFile> = MockChangesCollector(listener).also { collector = it }

  fun doHotSwap(session: HotSwapSession<MockVirtualFile>) = session.startHotSwapListening()
  override fun performHotSwap(session: HotSwapSession<MockVirtualFile>) {
    error("Not supported")
  }
}

internal class MockChangesCollector(private val listener: SourceFileChangesListener) : SourceFileChangesCollector<MockVirtualFile> {
  private val files = mutableSetOf<MockVirtualFile>()
  fun addFile(file: MockVirtualFile) {
    files.add(file)
    listener.onNewChanges()
  }

  override fun getChanges(): Set<MockVirtualFile> = files

  override fun resetChanges() {
    files.clear()
  }

  override fun dispose() {
  }
}
