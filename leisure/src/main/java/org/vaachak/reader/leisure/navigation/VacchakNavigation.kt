/*
 *  Copyright (c) 2026 Piyush Daiya
 *  *
 *  * Permission is hereby granted, free of charge, to any person obtaining a copy
 *  * of this software and associated documentation files (the "Software"), to deal
 *  * in the Software without restriction, including without limitation the rights
 *  * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  * copies of the Software, and to permit persons to whom the Software is
 *  * furnished to do so, subject to the following conditions:
 *  *
 *  * The above copyright notice and this permission notice shall be included in all
 *  * copies or substantial portions of the Software.
 *  *
 *  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  * SOFTWARE.
 */

package org.vaachak.reader.leisure.navigation

sealed class Screen(val route: String) {
    data object Library : Screen("library")
    data object Highlights : Screen("highlights")
    data object CatalogBrowser : Screen("catalog_browser")
    data object Settings : Screen("settings")
    data object Login : Screen("login")
    data object CatalogManage : Screen("catalog_manage")

    // UPDATED: Dynamic Route for Reader with optional Locator
    data object Reader : Screen("reader/{bookId}?locator={locator}") {
        fun createRoute(bookHash: String, locator: String? = null): String {
            return if (locator != null) {
                // Ensure locator is encoded if it contains special characters
                "reader/$bookHash?locator=$locator"
            } else {
                "reader/$bookHash"
            }
        }
    }

    // Settings Sub-screens
    data object AiConfig : Screen("ai_config")
    data object SyncSettings : Screen("sync_settings")
    data object Appearance : Screen("settings/appearance")
    data object AppAppearance : Screen("settings/app_appearance")
    data object Dictionary : Screen("settings/dictionary")

    data object TTS : Screen("settings/tts")

    data object Bookshelf : Screen("settings/bookshelf")
}
