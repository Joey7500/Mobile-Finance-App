package io.github.joey7500.vaultflow

import android.app.Application
import io.github.joey7500.vaultflow.di.AppContainer

class VaultFlowApp : Application() {

    @Volatile
    var container: AppContainer = AppContainer(this)
        private set

    /** Switching Demo Mode rebuilds the object graph against the other database. */
    @Synchronized
    fun rebuildContainer(): AppContainer {
        container.close()
        container = AppContainer(this)
        return container
    }
}
