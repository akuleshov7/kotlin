// WITH_RUNTIME
// INTENTION_TEXT: "Replace with 'firstOrNull{}'"
fun foo(list: List<String>) {
    var result = ""
    <caret>for (s in list) {
        if (s.length > 0) {
            result = bar(s)
            break
        }
    }
}

fun bar(s: String): String = s
