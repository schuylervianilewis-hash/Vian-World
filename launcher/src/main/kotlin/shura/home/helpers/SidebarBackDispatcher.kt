package shura.home.helpers

/**
 * Cross-module Back handoff from the launcher Activity to the optional Sidebar overlay.
 * The launcher module cannot depend on the app module, so the Sidebar coordinator registers
 * a handler here when it is initialized.
 */
object SidebarBackDispatcher {
    @Volatile
    private var handler: (() -> Boolean)? = null

    fun setHandler(handler: (() -> Boolean)?) {
        this.handler = handler
    }

    fun dispatchBack(): Boolean = handler?.invoke() == true
}
