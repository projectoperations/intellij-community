// PRIORITY: LOW
// AFTER-WARNING: Parameter 'action' is never used
// AFTER-WARNING: Parameter 't' is never used
package one.two.three

fun Int.test() = Unit

fun <T> myWith(t: T, action: T.() -> Unit) = Unit

fun check() {
    myWith(4, <caret>Int::test)
}
