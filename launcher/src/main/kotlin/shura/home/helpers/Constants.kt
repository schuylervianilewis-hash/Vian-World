package shura.home.helpers

const val WIDGET_LIST_SECTION = 0
const val WIDGET_LIST_ITEMS_HOLDER = 1

const val REPOSITORY_NAME = "Launcher"

// shared prefs
const val WAS_HOME_SCREEN_INIT = "was_home_screen_init"
const val HOME_ROW_COUNT = "home_row_count"
const val HOME_COLUMN_COUNT = "home_column_count"
const val DOCK_COLUMN_COUNT = "dock_column_count"
const val DRAWER_COLUMN_COUNT = "drawer_column_count"
const val SHOW_SEARCH_BAR = "show_search_bar"
const val CLOSE_APP_DRAWER = "close_app_drawer"
const val AUTO_SHOW_KEYBOARD_IN_APP_DRAWER = "auto_show_keyboard_in_app_drawer"
const val SHOW_DRAWER_APP_LABELS = "show_drawer_app_labels"
const val SHOW_HOME_APP_LABELS = "show_home_app_labels"
const val LOG_KEEPER_ENABLED = "log_keeper_enabled"
const val WAS_DEFAULT_CLOCK_ADDED = "was_default_clock_added"
const val APP_LOCK_ENABLED = "app_lock_enabled"
const val APP_LOCK_TIMEOUT = "app_lock_timeout"
const val APP_LOCK_USE_BIOMETRICS = "app_lock_use_biometrics"
const val APP_LOCK_PROTECT_HIDDEN_APPS = "app_lock_protect_hidden_apps"
const val DRAWER_SORT_BY = "drawer_sort_by"
const val DRAWER_SORT_ORDER = "drawer_sort_order"
const val GESTURE_DOUBLE_TAP_ACTION = "gesture_double_tap_action"
const val GESTURE_SWIPE_DOWN_ACTION = "gesture_swipe_down_action"
const val GESTURE_SWIPE_UP_ACTION = "gesture_swipe_up_action"
const val GESTURE_PINCH_IN_ACTION = "gesture_pinch_in_action"
const val FOLDER_COVER_MODE = "folder_cover_mode"
const val GESTURE_HAPTICS = "gesture_haptics"
const val SHOW_NOTIFICATION_BADGES = "show_notification_badges"
const val NEW_INSTALLED_PACKAGES = "new_installed_packages"
const val DRAWER_USE_CUSTOM_WALLPAPER = "drawer_use_custom_wallpaper"

const val DRAWER_SORT_BY_NAME = 1
const val DRAWER_SORT_BY_TIME = 2
const val DRAWER_SORT_ASCENDING = 1
const val DRAWER_SORT_DESCENDING = 2

const val APP_LOCK_TIMEOUT_IMMEDIATELY = 0
const val APP_LOCK_TIMEOUT_1_MIN = 60
const val APP_LOCK_TIMEOUT_5_MIN = 300
const val APP_LOCK_TIMEOUT_SCREEN_OFF = -1

// default home screen grid size (9 rows beside dock + 1 dock row = 10 total)
const val ROW_COUNT = 10
const val COLUMN_COUNT = 4
const val MIN_ROW_COUNT = 2
const val MAX_ROW_COUNT = 15
const val MIN_COLUMN_COUNT = 2
const val MAX_COLUMN_COUNT = 15

const val BUILT_IN_CLOCK_CLASS_NAME = "shura.home.built_in_clock_widget"
const val LEGACY_BUILT_IN_CLOCK_CLASS_NAME = "org.fossify.home.built_in_clock_widget"
fun isBuiltInClockWidget(className: String?): Boolean =
    className == BUILT_IN_CLOCK_CLASS_NAME || className == LEGACY_BUILT_IN_CLOCK_CLASS_NAME
const val WIDGET_ID_BUILTIN_CLOCK = -100

const val UNINSTALL_APP_REQUEST_CODE = 50
const val REQUEST_CONFIGURE_WIDGET = 51
const val REQUEST_ALLOW_BINDING_WIDGET = 52
const val REQUEST_CREATE_SHORTCUT = 53
const val REQUEST_SET_DEFAULT = 54

const val ITEM_TYPE_ICON = 0
const val ITEM_TYPE_WIDGET = 1
const val ITEM_TYPE_SHORTCUT = 2
const val ITEM_TYPE_FOLDER = 3
const val ITEM_TYPE_POPUP_WIDGET = 4

const val SIDEBAR_ELEMENT_CLASS_NAME = "vian_world_sidebar_element"

const val WIDGET_HOST_ID = 12345
const val MAX_CLICK_DURATION = 150
