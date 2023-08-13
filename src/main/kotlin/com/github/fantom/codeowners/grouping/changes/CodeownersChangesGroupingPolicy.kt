package com.github.fantom.codeowners.grouping.changes

import com.github.fantom.codeowners.CodeownersIcons
import com.github.fantom.codeowners.CodeownersManager
import com.github.fantom.codeowners.OwnersSet
import com.github.fantom.codeowners.indexing.OwnerString
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyKey
import com.intellij.openapi.vcs.changes.ui.*
import javax.swing.tree.DefaultTreeModel

class CodeownersChangesBrowserNode(owners: OwnersSet) : ChangesBrowserNode<OwnersSet>(owners) {
    override fun render(renderer: ChangesBrowserNodeRenderer, selected: Boolean, expanded: Boolean, hasFocus: Boolean) {
        super.render(renderer, selected, expanded, hasFocus)
        renderer.icon = CodeownersIcons.FILE
    }

    override fun getTextPresentation(): String {
        val owners = getUserObject()
        return if (owners.isEmpty()) {
            "<Unowned>"
        } else {
            owners.joinToString(", ")
        }
    }

    override fun compareUserObjects(o2: OwnersSet): Int {
        // unowned last
        // TODO sort also by owner type: i.e teams first
        return o2.size - getUserObject().size
    }
}

class CodeownersChangesGroupingPolicy(val project: Project, private val model: DefaultTreeModel) :
    BaseChangesGroupingPolicy() {
    private val codeownersManager = project.service<CodeownersManager>()

    @Suppress("ReturnCount")
    override fun getParentNodeFor(nodePath: StaticFilePath, subtreeRoot: ChangesBrowserNode<*>): ChangesBrowserNode<*>? {
        val nextPolicyParent = nextPolicy?.getParentNodeFor(nodePath, subtreeRoot)
        if (!codeownersManager.isAvailable) return nextPolicyParent

        val file = resolveVirtualFile(nodePath)
        file
            // TODO handle error properly
            ?.let { codeownersManager.getFileOwners(it).getOrNull() }
            ?.let { ownersRef ->
                val grandParent = nextPolicyParent ?: subtreeRoot
                val cachingRoot = getCachingRoot(grandParent, subtreeRoot)
                val owners = if (ownersRef.isEmpty()) {
                    emptySet()
                } else {
                    ownersRef.values.first().ref?.owners?.toSet() ?: emptySet()
                }
                CODEOWNERS_CACHE.getValue(cachingRoot).getOrPut(grandParent) { mutableMapOf() }[owners]?.let { return it }

                CodeownersChangesBrowserNode(owners).let {
                    it.markAsHelperNode()
                    model.insertNodeInto(it, grandParent, grandParent.childCount)

                    CODEOWNERS_CACHE.getValue(cachingRoot).getOrPut(grandParent) { mutableMapOf() }[owners] = it
                    return it
                }
            }
        return nextPolicyParent
    }

    internal class Factory : ChangesGroupingPolicyFactory() {
        override fun createGroupingPolicy(project: Project, model: DefaultTreeModel) =
            CodeownersChangesGroupingPolicy(project, model)
    }

    companion object {
        val CODEOWNERS_CACHE = NotNullLazyKey.createLazyKey<
            MutableMap<
                ChangesBrowserNode<*>,
                MutableMap<Set<OwnerString>, ChangesBrowserNode<*>>,
                >,
            ChangesBrowserNode<*>
            >("ChangesTree.CodeownersCache") { mutableMapOf() }
    }
}
