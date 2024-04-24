package com.github.fantom.codeowners.util

import com.github.fantom.codeowners.lang.CodeownersPatternBase
import com.github.fantom.codeowners.services.CodeownersMatcher
import com.github.fantom.codeowners.util.Utils.getRelativePath
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.jetbrains.rd.util.concurrentMapOf
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Glob util class that prepares glob statements or searches for content using glob rules.
 */
object Glob {

    /**
     * Finds for [VirtualFile] list using glob rule in given root directory.
     *
     * @param root  root directory
     * @param pattern ignore entry
     * @return search result
     */
    fun findOne(root: VirtualFile, pattern: CodeownersPatternBase, matcher: CodeownersMatcher) =
        find(root, listOf(pattern), matcher, false)[pattern]?.firstOrNull()

    /**
     * Finds for [VirtualFile] list using glob rule in given root directory.
     *
     * @param root          root directory
     * @param entries       codeowners entries
     * @param includeNested attach children to the search result
     * @return search result
     */
    fun find(root: VirtualFile, entries: List<CodeownersPatternBase>, matcher: CodeownersMatcher, includeNested: Boolean) =
        concurrentMapOf<CodeownersPatternBase, MutableList<VirtualFile>>().apply {
            val map = concurrentMapOf<CodeownersPatternBase, Pattern>()

            entries.forEach {
                this[it] = mutableListOf()
                it.pattern()?.let { pattern ->
                    map[it] = pattern
                }
            }

            val visitor = object : VirtualFileVisitor<Map<CodeownersPatternBase, Pattern?>>(NO_FOLLOW_SYMLINKS) {
                @Suppress("ReturnCount")
                override fun visitFile(file: VirtualFile): Boolean {
                    if (root == file) {
                        return true
                    }
                    val current = mutableMapOf<CodeownersPatternBase, Pattern?>()
                    val path = getRelativePath(root, file)
                    if (currentValue.isEmpty() || path == null) {
                        return false
                    }

                    currentValue.forEach { (key, value) ->
                        var matches = false
                        if (value == null || matcher.match(value, path)) {
                            matches = true
                            get(key)?.add(file)
                        }
                        current[key] = value.takeIf { !includeNested || !matches }
                    }

                    setValueForChildren(current)
                    return true
                }
            }

            visitor.setValueForChildren(map)
            VfsUtil.visitChildrenRecursively(root, visitor)
        }

    /**
     * Creates regex [Pattern] using glob rule.
     *
     * @param rule   rule value
     * @return regex [Pattern]
     */
    fun createPattern(rule: String, acceptChildren: Boolean = false, supportSquareBrackets: Boolean) =
        getPattern(createRegex(rule, acceptChildren, supportSquareBrackets))

    /**
     * Converts regex string to [Pattern] with caching.
     *
     * @param regex regex to convert
     * @return [Pattern] instance or null if invalid
     */
    fun getPattern(regex: String) = try {
        Pattern.compile(regex)
    } catch (e: PatternSyntaxException) {
        null
    }

    fun createFragmentRegex(fragment: CharSequence): String {
        // don't put start/end anchors to reuse this function in glob -> regex conversion
        val sb = StringBuilder()
        fragment.forEach {
            when (it) {
                '?' -> sb.append(".")
                '*' -> sb.append(".*")
                '\\' -> {} // skip escaping characters
                else -> sb.append(it)
            }
        }
        return sb.toString()
    }

    /**
     * Creates regex [String] using glob rule.
     *
     * @param glob           rule
     * @param acceptChildren Matches directory children
     * @return regex [String]
     */
    @Suppress("ComplexMethod", "LongMethod", "NestedBlockDepth")
    fun createRegex(glob: String, acceptChildren: Boolean, supportSquareBrackets: Boolean): String = glob.trim { it <= ' ' }.let {
        val sb = StringBuilder("^")
        var escape = false
        var star = false
        var doubleStar = false
        var bracket = false
        var beginIndex = 0

        if (StringUtil.startsWith(it, Constants.DOUBLESTAR)) {
            sb.append("(?:[^/]*?/)*")
            beginIndex = 2
            doubleStar = true
        } else if (StringUtil.startsWith(it, "*/")) {
            sb.append("[^/]*")
            beginIndex = 1
            star = true
        } else if (StringUtil.equals(Constants.STAR, it)) {
            sb.append(".*")
        } else if (StringUtil.startsWithChar(it, '*')) {
            sb.append(".*?")
        } else if (StringUtil.startsWithChar(it, '/')) {
            beginIndex = 1
        } else {
            val slashes = StringUtil.countChars(it, '/')
            if (slashes == 0 || slashes == 1 && StringUtil.endsWithChar(it, '/')) {
                sb.append("(?:[^/]*?/)*")
            }
        }

        val chars = it.substring(beginIndex).toCharArray()
        chars.forEach { ch ->
            if (supportSquareBrackets && bracket && ch != ']') {
                sb.append(ch)
                return@forEach
            } else if (doubleStar) {
                doubleStar = false
                if (ch == '/') {
                    sb.append("(?:[^/]*/)*?")
                    return@forEach
                } else {
                    sb.append("[^/]*?")
                }
            }
            if (ch == '*') {
                when {
                    escape -> {
                        sb.append("\\*")
                        star = false
                        escape = star
                    }
                    star -> {
                        val prev = if (sb.isNotEmpty()) sb[sb.length - 1] else '\u0000'
                        if (prev == '\u0000' || prev == '^' || prev == '/') {
                            doubleStar = true
                        } else {
                            sb.append("[^/]*?")
                        }
                        star = false
                    }
                    else -> {
                        star = true
                    }
                }
                return@forEach
            } else if (star) {
                sb.append("[^/]*?")
                star = false
            }
            when {
                ch == '\\' -> {
                    if (escape) {
                        sb.append("\\\\")
                    }
                    escape = !escape
                }
                ch == '?' ->
                    if (escape) {
                        sb.append("\\?")
                        escape = false
                    } else {
                        sb.append('.')
                    }
                ch == '[' && supportSquareBrackets -> {
                    if (escape) {
                        sb.append('\\')
                        escape = false
                    } else {
                        bracket = true
                    }
                    sb.append(ch)
                }
                ch == ']' && supportSquareBrackets -> {
                    if (!bracket) {
                        sb.append('\\')
                    }
                    sb.append(ch)
                    bracket = false
                    escape = false
                }
                ch in arrayOf('.', '(', ')', '{', '}', '+', '|', '^', '$', '@', '%') ||
                    (!supportSquareBrackets && ch in arrayOf('[', ']')) -> {
                    sb.append('\\')
                    sb.append(ch)
                    escape = false
                }
                else -> {
                    escape = false
                    sb.append(ch)
                }
            }
        }
        when {
            StringUtil.endsWithChar(sb, '/') -> when {
                star -> sb.append("[^/]+") // or *
//                doubleStar -> sb.append(".+")
//                else -> if (acceptChildren) sb.append("[^/]*")
                else -> sb.append(".*")
            }
            star || doubleStar -> sb.append("[^/]*/?")
            else -> sb.append(if (acceptChildren) "(?:/.*)?" else "/?")
        }
//        if (star || doubleStar) {
//            if (StringUtil.endsWithChar(sb, '/')) {
//                if (doubleStar) {
//                    sb.append(".+")
//                } else {
//                    sb.append("[^/]*") // or +
//                }
//            } else {
//                sb.append("[^/]*/?")
//            }
//        } else {
//            if (StringUtil.endsWithChar(sb, '/')) {
//                if (acceptChildren) {
//                    sb.append("[^/]*")
//                }
//            } else {
//                sb.append(if (acceptChildren) "(?:/.*)?" else "/?")
//            }
//        }
        sb.append('$')
        return sb.toString()
    }

    fun unescape(text: CharSequence): CharSequence {
        val (_, res) = text.fold(Pair(false, StringBuilder())) { (escape, sb), ch ->
            when (ch) {
                '\\' -> if (escape) {
                    Pair(false, sb.append(ch)) // if already escaping, add char without backslash
                } else {
                    Pair(true, sb) // just started escaping, skip backslash
                }
                else -> Pair(false, sb.append(ch)) // simply add char
            }
        }
        return res
    }

    @Suppress("UnusedPrivateMember")
    fun createPrefixRegex(prefixGlob: CharSequence, atAnyLevel: Boolean, dirOnly: Boolean): Regex {
        val sb = StringBuilder("^")
        // TODO can we take dirOnly into account? How dir paths are passed in events?
        val fragments = prefixGlob.split('/')
        val (head, tail) = if (fragments.size > 1) {
            Pair(fragments.first(), fragments.drop(1))
        } else {
            Pair(fragments.first(), emptyList())
        }
        val tailRegex = tail.foldRight("") { fragment, suffix ->
            val fragmentRegex = createFragmentRegex(fragment)
            "($fragmentRegex/$suffix)?"
        }
        val headRegex = createFragmentRegex(head)
        var regexStr = "$headRegex/$tailRegex"
        if (atAnyLevel) {
            regexStr = ".*$regexStr"
        }
        return Regex(sb.append(regexStr).toString())
    }

    @Suppress("ComplexMethod", "LongMethod", "NestedBlockDepth")
    fun createDregex(glob: String, acceptChildren: Boolean, supportSquareBrackets: Boolean): String = glob.trim { it <= ' ' }.let {
        val sb = StringBuilder()
        var escape = false
        var star = false
        var doubleStar = false
        var bracket = false
        var beginIndex = 0

        if (StringUtil.startsWith(it, Constants.DOUBLESTAR)) {
            sb.append("(?:[^/]*/)*")
            beginIndex = 2
            doubleStar = true
        } else if (StringUtil.startsWith(it, "*/")) {
            sb.append("[^/]*")
            beginIndex = 1
            star = true
        } else if (StringUtil.equals(Constants.STAR, it)) {
            sb.append(".*")
        } else if (StringUtil.startsWithChar(it, '*')) {
            sb.append(".*")
        } else if (StringUtil.startsWithChar(it, '/')) {
            beginIndex = 1
        } else {
            val slashes = StringUtil.countChars(it, '/')
            if (slashes == 0 || slashes == 1 && StringUtil.endsWithChar(it, '/')) {
                sb.append("(?:[^/]*/)*")
            }
        }

        val chars = it.substring(beginIndex).toCharArray()
        chars.forEach { ch ->
            if (supportSquareBrackets && bracket && ch != ']') {
                sb.append(ch)
                return@forEach
            } else if (doubleStar) {
                doubleStar = false
                if (ch == '/') {
                    sb.append("(?:[^/]*/)*")
                    return@forEach
                } else {
                    sb.append("[^/]*")
                }
            }
            if (ch == '*') {
                when {
                    escape -> {
                        sb.append("\\*")
                        star = false
                        escape = star
                    }
                    star -> {
                        val prev = if (sb.isNotEmpty()) sb[sb.length - 1] else '\u0000'
                        if (prev == '\u0000' || prev == '^' || prev == '/') {
                            doubleStar = true
                        } else {
                            sb.append("[^/]*")
                        }
                        star = false
                    }
                    else -> {
                        star = true
                    }
                }
                return@forEach
            } else if (star) {
                sb.append("[^/]*")
                star = false
            }
            when {
                ch == '\\' -> {
                    if (escape) {
                        sb.append("\\\\")
                    }
                    escape = !escape
                }
                ch == '?' ->
                    if (escape) {
                        sb.append("\\?")
                        escape = false
                    } else {
                        sb.append('.')
                    }
                ch == '[' && supportSquareBrackets -> {
                    if (escape) {
                        sb.append('\\')
                        escape = false
                    } else {
                        bracket = true
                    }
                    sb.append(ch)
                }
                ch == ']' && supportSquareBrackets -> {
                    if (!bracket) {
                        sb.append('\\')
                    }
                    sb.append(ch)
                    bracket = false
                    escape = false
                }
                ch in arrayOf('.', '(', ')', '{', '}', '+', '|', '^', '$', '@', '%') ||
                    (!supportSquareBrackets && ch in arrayOf('[', ']')) -> {
                    sb.append('\\')
                    sb.append(ch)
                    escape = false
                }
                else -> {
                    escape = false
                    sb.append(ch)
                }
            }
        }
        when {
            StringUtil.endsWithChar(sb, '/') -> when {
                star -> sb.append("[^/]+") // or *
//                doubleStar -> sb.append(".+")
//                else -> if (acceptChildren) sb.append("[^/]*")
                else -> sb.append(".*")
            }
            star || doubleStar -> sb.append("[^/]*/?")
            else -> sb.append(if (acceptChildren) "(?:/.*)?" else "/?")
        }
//        if (star || doubleStar) {
//            if (StringUtil.endsWithChar(sb, '/')) {
//                if (doubleStar) {
//                    sb.append(".+")
//                } else {
//                    sb.append("[^/]*") // or +
//                }
//            } else {
//                sb.append("[^/]*/?")
//            }
//        } else {
//            if (StringUtil.endsWithChar(sb, '/')) {
//                if (acceptChildren) {
//                    sb.append("[^/]*")
//                }
//            } else {
//                sb.append(if (acceptChildren) "(?:/.*)?" else "/?")
//            }
//        }
        return sb.toString()
    }
}
