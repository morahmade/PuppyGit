package com.catpuppyapp.puppygit.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.catpuppyapp.puppygit.data.entity.RepoEntity
import com.catpuppyapp.puppygit.notification.AutomationNotify
import com.catpuppyapp.puppygit.notification.base.ServiceNotify
import com.catpuppyapp.puppygit.notification.util.NotifyUtil
import com.catpuppyapp.puppygit.server.bean.NotificationSender
import com.catpuppyapp.puppygit.settings.AppSettings
import com.catpuppyapp.puppygit.settings.SettingsUtil
import com.catpuppyapp.puppygit.settings.util.AutomationUtil
import com.catpuppyapp.puppygit.utils.AppModel
import com.catpuppyapp.puppygit.utils.ContextUtil
import com.catpuppyapp.puppygit.utils.MyLog
import com.catpuppyapp.puppygit.utils.RepoActUtil
import com.catpuppyapp.puppygit.utils.cache.NotifySenderMap
import com.catpuppyapp.puppygit.utils.doJobThenOffLoading
import com.catpuppyapp.puppygit.utils.generateRandomString
import io.ktor.util.collections.ConcurrentMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


class AutomationService: AccessibilityService() {
    companion object {
        private const val TAG = "AutomationService"

        private val lock = Mutex()
        // key is package name, value is true=app opened, false=app closed, null=app never open
        private val targetPackageTrueOpenedFalseCloseNullNeverOpenedList = ConcurrentMap<String, Boolean>()
        // key is package name, value is time app last open time in millseconds, if null, means never open
//        private val lastPullTime = ConcurrentMap<String, Long>()
        //app last leaving at, key is package name, value is time in millseconds
        private val appLeaveTime = ConcurrentMap<String, Long>()

        private var lastTargetPackageName = ""  // use to check enter/leave app

        private val ignorePackageNames = listOf<String>(
            "com.android.systemui",  //通知栏或全面屏手势之类的
            "android",  //切换输入法的弹窗，有的系统（例如原生）会是这个包名，有的不是（例如国产color os）
            "com.oplus.notificationmanager",  //oneplus等oppo系系统，安装app，初次启动，弹窗，询问“是否允许当前app显示通知”
            "com.google.android.permissioncontroller",  //原生系统安装app后首次启动时询问是否允许通知的弹窗，不确定其他权限（例如“获取位置信息”）的弹窗是否也和这个有关


        )


        private fun createNotify(notifyId:Int):ServiceNotify {
            return ServiceNotify(AutomationNotify.create(notifyId))
        }


        private fun sendSuccessNotificationIfEnable(serviceNotify: ServiceNotify, settings: AppSettings) = { title:String?, msg:String?, startPage:Int?, startRepoId:String? ->
            if(settings.automation.showNotifyWhenProgress) {
                serviceNotify.sendSuccessNotification(title, msg, startPage, startRepoId)
            }
        }

        private fun sendErrNotificationIfEnable(serviceNotify: ServiceNotify, settings: AppSettings)={ title:String, msg:String, startPage:Int, startRepoId:String ->
            if(settings.automation.showNotifyWhenProgress) {
                serviceNotify.sendErrNotification(title, msg, startPage, startRepoId)
            }
        }

        private fun sendProgressNotificationIfEnable(serviceNotify: ServiceNotify, settings: AppSettings) = { repoNameOrId:String, progress:String ->
            if(settings.automation.showNotifyWhenProgress) {
                serviceNotify.sendProgressNotification(repoNameOrId, progress)
            }
        }


        private suspend fun pullRepoList(
            sessionId:String,
            settings: AppSettings,
            repoList:List<RepoEntity>,
        ) {
            if(AppModel.devModeOn) {
                MyLog.d(TAG, "#pullRepoList: generate notifyers for ${repoList.size} repos")
            }

            repoList.forEach {
                //notify
                val serviceNotify = createNotify(NotifyUtil.genId())
                NotifySenderMap.set(
                    NotifySenderMap.genKey(it.id, sessionId),
                    NotificationSender(
                        sendErrNotificationIfEnable(serviceNotify, settings),
                        sendSuccessNotificationIfEnable(serviceNotify, settings),
                        sendProgressNotificationIfEnable(serviceNotify, settings),
                    )
                )
            }

            RepoActUtil.pullRepoList(
                sessionId,
                repoList,
                routeName = "'automation pull service'",
                gitUsernameFromUrl="",
                gitEmailFromUrl="",
            )
        }


        private suspend fun pushRepoList(
            sessionId:String,
            settings: AppSettings,
            repoList:List<RepoEntity>,
        ) {

            if(AppModel.devModeOn) {
                MyLog.d(TAG, "#pushRepoList: generate notifyers for ${repoList.size} repos")
            }

            repoList.forEach {
                //notify
                val serviceNotify = createNotify(NotifyUtil.genId())
                NotifySenderMap.set(
                    NotifySenderMap.genKey(it.id, sessionId),
                    NotificationSender(
                        sendErrNotificationIfEnable(serviceNotify, settings),
                        sendSuccessNotificationIfEnable(serviceNotify, settings),
                        sendProgressNotificationIfEnable(serviceNotify, settings),
                    )
                )
            }

            RepoActUtil.pushRepoList(
                sessionId,
                repoList,
                routeName = "'automation push service'",
                gitUsernameFromUrl="",
                gitEmailFromUrl="",
                autoCommit=true,
                force=false,
            )
        }


    }


    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ContextUtil.getLocalizedContext(newBase))
    }


    override fun onCreate() {
        super.onCreate()

        // 初始化代码
        AppModel.init_1(realAppContext = applicationContext, exitApp = {}, initActivity = false)

        runBlocking {
            AppModel.init_2()
        }

        MyLog.w(TAG, "#onCreate() finished")

    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if(event == null) {

            if(AppModel.devModeOn) {
                MyLog.d(TAG, "#onAccessibilityEvent: event is null")
            }

            return
        }

        if(event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            //必须在外部获取，放到协程里会null
            val packageName = event.packageName.toString()

            if(AppModel.devModeOn) {
                MyLog.v(TAG, "TYPE_WINDOW_STATE_CHANGED: $packageName")
            }

            // 若是期望忽略的包名则返回
            if(ignorePackageNames.contains(packageName)) {

                if(AppModel.devModeOn) {
                    MyLog.d(TAG, "ignore package name: '$packageName'")
                }

                return
            }

            try {
                //ignore input method package names
                if((getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.enabledInputMethodList?.find { it.packageName == packageName } != null) {

                    if(AppModel.devModeOn) {
                        MyLog.d(TAG, "ignore input method (soft keyboard): '$packageName'")
                    }

                    return
                }
            }catch (e:Exception) {
                MyLog.d(TAG, "get enabledInputMethodList err: ${e.stackTraceToString()}")
            }

            val settings = SettingsUtil.getSettingsSnapshot()
            val targetPackageList = AutomationUtil.getPackageNames(settings.automation)

            //如果目标app列表为空，就不需要后续判断了，直接返回
            if(targetPackageList.isEmpty()) {
                return
            }

            doJobThenOffLoading {
                val event = Unit  //覆盖外部event变量名，避免错误捕获

                lock.withLock {
                    val sessionId = generateRandomString()

                    if(targetPackageList.contains(packageName)) {  // 是我们关注的app
                        lastTargetPackageName = packageName

                        val lastTargetPackageName = Unit  // to avoid mistake using

                        MyLog.d(TAG, "target packageName '$packageName' opened, checking need pull or no....")

                        val targetOpened = targetPackageTrueOpenedFalseCloseNullNeverOpenedList[packageName] == true
                        if(!targetOpened) { // was leave, now opened or first time opened （初次打开或离开又重新打开）
                            targetPackageTrueOpenedFalseCloseNullNeverOpenedList[packageName] = true
                            MyLog.d(TAG, "target packageName '$packageName' opened, need do pull")

                            val bindRepoIds = AutomationUtil.getRepoIds(settings.automation, packageName)
                            if(bindRepoIds.isEmpty()) {
                                return@withLock
                            }
                            val db = AppModel.dbContainer
                            val repoList = db.repoRepository.getAll().filter { bindRepoIds.contains(it.id) }
                            if(repoList.isEmpty()) {
                                return@withLock
                            }


                            //do pull
                            val pullIntervalInMillSec = settings.automation.pullIntervalInSec * 1000L
                            //负数将禁用pull
                            if(pullIntervalInMillSec >= 0L) {
                                //离开app后，在一定时间间隔内返回，将不会重复执行pull
                                val lastLeaveAt = appLeaveTime[packageName] ?: 0L ;

                                doJobThenOffLoading pullTask@{
                                    // pullIntervalInMillSec == 0 代表用户设置的pull间隔为0，无间隔，直接执行
                                    // lastLeaveAt == 0 代表没离开过，初次打开app，这时应该直接执行操作，不用检测间隔
                                    // 最后一个条件是检测时间间隔
                                    if(pullIntervalInMillSec == 0L || lastLeaveAt == 0L || (System.currentTimeMillis() - lastLeaveAt) > pullIntervalInMillSec) {
                                        pullRepoList(sessionId, settings, repoList)
                                    }
                                }
                            }
                        }else {
                            MyLog.d(TAG, "target packageName '$packageName' opened but no need do pull")
                        }
                    }else if(lastTargetPackageName.isNotBlank()) { //当前app不是我们关注的app，但上个是
                        val packageName = Unit  //避免在这个代码块误调用这个变量名

                        val lastOpenedTarget = lastTargetPackageName
                        lastTargetPackageName = ""

                        val lastTargetPackageName = Unit  // for avoid mistake using this variable

                        //这里需要判断，不然用户更新列表后，这里若不判断会对之前在列表但后来移除的条目多执行一次pull
                        if(targetPackageList.contains(lastOpenedTarget)) {
                            MyLog.d(TAG, "target packageName '$lastOpenedTarget' leaved, checking need push or no...")
                            //这个应该百分百为真啊？
                            val targetOpened = targetPackageTrueOpenedFalseCloseNullNeverOpenedList[lastOpenedTarget] == true
                            if(targetOpened) { // was opened, now leave
                                MyLog.d(TAG, "target packageName '$lastOpenedTarget' leaved, need do push")
                                targetPackageTrueOpenedFalseCloseNullNeverOpenedList[lastOpenedTarget] = false
                                appLeaveTime[lastOpenedTarget] = System.currentTimeMillis()

                                val bindRepoIds = AutomationUtil.getRepoIds(settings.automation, lastOpenedTarget)
                                if(bindRepoIds.isEmpty()) {
                                    return@withLock
                                }
                                val db = AppModel.dbContainer
                                val repoList = db.repoRepository.getAll().filter { bindRepoIds.contains(it.id) }
                                if(repoList.isEmpty()) {
                                    return@withLock
                                }

                                // do push, one package may bind multi repos, start a coroutine do push for them
                                val pushDelayInMillSec = settings.automation.pushDelayInSec * 1000L
                                //负数将禁用push
                                if(pushDelayInMillSec >= 0L) {
                                    doJobThenOffLoading pushTask@{
                                        var taskCanceled = false

                                        //大于0，等待超过延迟时间后再执行操作；若等于0，则不检查，直接跳过这段，执行后面的push
                                        if(pushDelayInMillSec > 0L) {
                                            val startAt = System.currentTimeMillis()

                                            while (true) {
                                                // 每 2 秒检查一次是否需要push，虽然设置的单位是秒，但精度是2秒，避免太多无意义cpu空转，最多误差2秒，可接受
                                                delay(2000L)

                                                // 若app重新打开 或者 app在当前任务启动后重新触发了离开（通过leave时间判断），则取消当前push任务
                                                //这里的两个值必须每次都从map里获取最新的数据，因为map里的值会在每次离开app后更新
                                                if(
                                                    // app重新打开，当前任务取消
                                                    targetPackageTrueOpenedFalseCloseNullNeverOpenedList[lastOpenedTarget] == true
                                                    // app再次离开，当前任务已“过期”，应取消
                                                    //正常来说，这里获取的app离开时间永远不可能为null，所以 `?: 0L` 应该不会派上用场，
                                                    // 如果出了问题，真的为null，则代表app没离开，这时不应执行push，或者忽略这个条件都行，这里采用后者，
                                                    // startAt肯定大于0，所以如果appLeaveTime取出的值为0的话，这个条件永远为假，相当于忽略了，其实就算错误执行一次push也问题不大
                                                    || startAt < (appLeaveTime[lastOpenedTarget] ?: 0L)
                                                ) {
                                                    taskCanceled = true
                                                    break
                                                }

                                                //如果当前时间减起始时间超过了设定的延迟时间则执行push
                                                if((System.currentTimeMillis() - startAt) > pushDelayInMillSec) {
                                                    break
                                                }
                                            }
                                        }

                                        if(!taskCanceled) {
                                            pushRepoList(sessionId, settings, repoList)
                                        }
                                    }
                                }

                            }else {
                                MyLog.d(TAG, "target packageName '$packageName' opened but no need do push")
                            }
                        }else {  //这里得设置下，如果一个条目之前在列表后来移除了，这里不更新下的话，下次打开目标app会忽略一次应该执行的pull
                            MyLog.d(TAG, "target packageName '$lastOpenedTarget' was in targetList but removed, will not do push for it")
                            targetPackageTrueOpenedFalseCloseNullNeverOpenedList[lastOpenedTarget] = false
                        }
                    }
                }
            }
        }
    }


    override fun onInterrupt() {
        if(AppModel.devModeOn) {
            MyLog.w(TAG, "#onInterrupt: interrupted?")
        }
    }

}
