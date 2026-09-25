package com.aliothmoon.maameow.domain.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 打卡结束时的收尾动作登记处。
 *
 * ## 解决什么问题
 *
 * 打卡结束后可能需要做几件事：关闭目标应用、熄屏、释放屏保。
 * 但这些动作**不能在打卡刚发起时就执行**（那时应用还没关），
 * 也不能简单地"等 N 秒后执行"（不同设备加载速度差异大）。
 *
 * 正确做法是：打卡流程登记一个"待执行的收尾动作"，
 * 等真正执行完毕后再触发它。
 *
 * ## 相比原 MAA-Meow 工程的改动
 *
 * 原实现监听 MAA 核心状态机（RUNNING / STOPPING / IDLE / ERROR）
 * 判断"任务是否结束"，并区分自然结束、用户手动停止、掉线中止等
 * 四种结束原因 —— 因为游戏自动化是长任务，结束路径很多。
 *
 * 打卡是一次性短任务，完成即结束，没有这些中间状态。
 * 因此改为由打卡流程**显式调用** [onFinished] 通知结束，
 * 比监听状态机更直接，也更容易理解。
 */
class TaskEndRegistry(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** 结束原因。 */
    enum class Reason {
        /** 自然完成（打卡成功或失败，流程走完） */
        NATURAL,

        /** 用户手动停止 */
        MANUAL,

        /** 异常中止（超时、被系统回收等） */
        ABORTED,
    }

    fun interface PendingAction {
        suspend fun run(reason: Reason)
    }

    private val _taskEnded = MutableSharedFlow<Reason>(extraBufferCapacity = 4)

    /** 结束事件广播，随订阅方 scope 自动取消 */
    val taskEnded: SharedFlow<Reason> = _taskEnded.asSharedFlow()

    private val pending = AtomicReference<PendingAction?>(null)
    private val started = AtomicBoolean(false)

    /** 登记本次收尾动作；重复登记会覆盖前一次。 */
    fun armOnce(action: PendingAction) {
        pending.set(action)
        Timber.d("TaskEndRegistry: 已登记收尾动作")
    }

    /** 撤销登记，防止上一轮的收尾动作落到新一轮上。 */
    fun disarmOnce() {
        pending.set(null)
        Timber.d("TaskEndRegistry: 已撤销收尾动作")
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        Timber.i("TaskEndRegistry: 已就绪")
    }

    /**
     * 通知打卡流程已结束，触发已登记的收尾动作。
     *
     * 由打卡执行流程在 finally 块中调用，确保无论成功失败都会收尾。
     */
    fun onFinished(reason: Reason) {
        scope.launch {
            Timber.i("TaskEndRegistry: 打卡结束，原因=%s", reason)
            runPending(reason)
            _taskEnded.tryEmit(reason)
        }
    }

    /** 执行并清空待处理动作。取出后立即置空，避免重复执行。 */
    private suspend fun runPending(reason: Reason) {
        val action = pending.getAndSet(null) ?: return
        try {
            action.run(reason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "TaskEndRegistry: 收尾动作执行失败")
        }
    }
}
