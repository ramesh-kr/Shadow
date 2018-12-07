package com.tencent.shadow.core.loader

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.content.res.Resources
import android.os.IBinder
import com.tencent.shadow.core.loader.classloaders.PluginClassLoader
import com.tencent.shadow.core.loader.delegates.ShadowDelegate
import com.tencent.shadow.core.loader.managers.ComponentManager
import com.tencent.shadow.runtime.ShadowApplication
import com.tencent.shadow.runtime.ShadowService

/**
 * 插件service管理类，负责插件框架内所有service启动，销毁，生命周期管理
 * Created by jaylanchen on 2018/11/29.
 */

class PluginServiceManager(private val mPluginLoader: ShadowPluginLoader, private val mHostContext: Context) {

    // 保存service的binder
    private val mServiceBinderMap = HashMap<ComponentName, IBinder?>()
    // service对应ServiceConnection集合
    private val mServiceConnectionMap = HashMap<ComponentName, HashSet<ServiceConnection>>()
    // ServiceConnection与对应的Intent的集合
    private val mConnectionIntentMap = HashMap<ServiceConnection, Intent>()
    // 所有已启动的service集合
    private val mAliveServicesMap = HashMap<ComponentName, ShadowService>()
    // 通过startService启动起来的service集合
    private val mServiceStartByStartServiceSet = HashSet<ComponentName>()
    // 存在mAliveServicesMap中，且stopService已经调用的service集合
    private val mServiceStopCalledMap = HashSet<ComponentName>()

    private val allDelegates: Collection<ShadowService>
        get() = mAliveServicesMap.values

    companion object {
        private var startId: Int = 0
        fun getNewStartId(): Int {
            startId++

            return startId
        }
    }


    fun startPluginService(intent: Intent): ComponentName? {
        val componentName = intent.component


        // 检查所请求的service是否已经存在
        if (!mAliveServicesMap.containsKey(componentName)) {
            // 不存在则创建
            val service = createServiceAndCallOnCreate(intent)
            mAliveServicesMap[componentName] = service
            // 通过startService启动集合
            mServiceStartByStartServiceSet.add(componentName)
        }
        mAliveServicesMap[componentName]?.onStartCommand(intent, 0, getNewStartId())


        return componentName
    }

    fun stopPluginService(intent: Intent): Boolean {
        val componentName = intent.component

        if (mAliveServicesMap.containsKey(componentName)) {
            mServiceStopCalledMap.add(componentName)

            // 看是否需要结束掉该service
            return destroyServiceIfNeed(componentName)
        }

        return false
    }

    fun bindPluginService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
        // todo 目前实现未处理flags,后续实现补上

        val componentName = intent.component

        // 1. 看要bind的service是否创建并在运行了
        if (!mAliveServicesMap.containsKey(componentName)) {
            // 如果还没创建，则创建,并保持
            val service = createServiceAndCallOnCreate(intent)
            mAliveServicesMap[componentName] = service
        }

        val service = mAliveServicesMap[componentName]!!

        // 2. 检查是否该Service之前是否被绑定过了
        if (!mServiceBinderMap.containsKey(componentName)) {
            // 还没调用过onBinder,在这里调用
            mServiceBinderMap[componentName] = service.onBind(intent)
        }

        // 3. 如果binder不为空，则要回调onServiceConnected
        mServiceBinderMap[componentName]?.let {


            // 检查该connections是否存在了
            if (mServiceConnectionMap.containsKey(componentName)) {

                if (!mServiceConnectionMap[componentName]!!.contains(conn)) {
                    // 如果service的bind connection集合中不包含该connection,则加入
                    mServiceConnectionMap[componentName]!!.add(conn)
                    mConnectionIntentMap[conn] = intent


                    // 回调onServiceConnected
                    conn.onServiceConnected(componentName, it)
                } else {
                    // 已经包含该connection了，说明onServiceConnected已经回调过了，所以这里什么也不用干
                }

            } else {
                // 该connection是第一个bind connection
                val connectionSet = HashSet<ServiceConnection>()
                connectionSet.add(conn)
                mServiceConnectionMap[componentName] = connectionSet
                mConnectionIntentMap[conn] = intent

                // 回调onServiceConnected
                conn.onServiceConnected(componentName, it)
            }
        }

        return true

    }

    fun unbindPluginService(connection: ServiceConnection) {

        for ((componentName, connSet) in mServiceConnectionMap) {
            if (connSet.contains(connection)) {
                connSet.remove(connection)
                val intent = mConnectionIntentMap.remove(connection)

                if (connSet.size == 0) {
                    // 已经没有任何connection了，mServiceConnectionMap移除该service数据
                    mServiceConnectionMap.remove(componentName)

                    // 所有connection都unbind了
                    mAliveServicesMap[componentName]?.onUnbind(intent!!)
                }

                // 结束该service
                destroyServiceIfNeed(componentName)

                break
            }
        }

    }



    fun onConfigurationChanged(newConfig: Configuration?) {
        allDelegates.forEach {
            it.onConfigurationChanged(newConfig)
        }
    }

    fun onLowMemory() {
        allDelegates.forEach {
            it.onLowMemory()
        }
    }

    fun onTrimMemory(level: Int) {
        allDelegates.forEach {
            it.onTrimMemory(level)
        }
    }


    fun onTaskRemoved(rootIntent: Intent) {
        allDelegates.forEach {
            it.onTaskRemoved(rootIntent)
        }
    }


    fun onDestroy() {
        mServiceBinderMap.clear()
        mServiceConnectionMap.clear()
        mConnectionIntentMap.clear()
        mAliveServicesMap.clear()
        mServiceStartByStartServiceSet.clear()
        mServiceStopCalledMap.clear()
    }


    private fun createServiceAndCallOnCreate(intent: Intent): ShadowService {
        val service = newServiceInstance(intent.component)
        service.onCreate()
        return service
    }


    private fun newServiceInstance(componentName: ComponentName): ShadowService {
        val partKey = mPluginLoader.mComponentManager.getComponentPartKey(componentName)
        val className = componentName.className

        val tmpShadowDelegate = TmpShadowDelegate()
        mPluginLoader.inject(tmpShadowDelegate, partKey!!)
        val serviceClazz = tmpShadowDelegate.getPluginClassLoader().loadClass(className)
        val service = ShadowService::class.java.cast(serviceClazz.newInstance())

        service.setHostContextAsBase(mHostContext)
        service.setPluginResources(tmpShadowDelegate.getPluginResources())
        service.setPluginClassLoader(tmpShadowDelegate.getPluginClassLoader())
        service.setShadowApplication(tmpShadowDelegate.getPluginApplication())
        service.setPluginComponentLauncher(tmpShadowDelegate.getComponentManager())
        service.setLibrarySearchPath(tmpShadowDelegate.getPluginClassLoader().getLibrarySearchPath())
        service.setPluginPartKey(partKey)

        return service
    }


    private fun destroyServiceIfNeed(service: ComponentName): Boolean {

        val destroy = {
            // 移除该service，及相关数据
            val serviceDelegate = mAliveServicesMap.remove(service)
            mServiceStopCalledMap.remove(service)
            mServiceBinderMap.remove(service)
            mServiceStartByStartServiceSet.remove(service)

            // 调用service的onDestroy
            serviceDelegate!!.onDestroy()
        }

        // 如果不是通过startService启动的，则所有connection unbind后就可以结束了
        if (!mServiceStartByStartServiceSet.contains(service)) {
            if (mServiceConnectionMap[service] == null) {
                // 结束该service
                destroy()
                return true
            }
        } else {
            // 如果该service，有通过startService,则必须调用过stopService且没有bind了，才能销毁
            if (mServiceStopCalledMap.contains(service) && !mServiceConnectionMap.containsKey(service)) {
                // 结束该service
                destroy()
                return true
            }

        }

        return false
    }

}

private class TmpShadowDelegate : ShadowDelegate() {

    fun getPluginApplication(): ShadowApplication = mPluginApplication
    fun getPluginClassLoader(): PluginClassLoader = mPluginClassLoader
    fun getPluginResources(): Resources = mPluginResources
    fun getComponentManager(): ComponentManager = mComponentManager
}
