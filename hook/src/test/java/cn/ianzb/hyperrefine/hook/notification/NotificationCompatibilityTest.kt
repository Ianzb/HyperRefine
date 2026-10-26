package cn.ianzb.hyperrefine.hook.notification

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class NotificationCompatibilityTest {
    open class Parent {
        private var importance = 3
        open fun attach(value: String) {}
        fun attach(value: Int) {}
    }

    class Child : Parent() {
        override fun attach(value: String) {}
    }

    open class Mapper {
        open fun invoke(value: Any): Any = value
    }

    class SpecializedMapper : Mapper() {
        override fun invoke(value: Any): String = value.toString()
    }

    interface ChangeListener {
        fun onPreferenceChange(preference: Any?, value: Any?): Boolean
    }

    @Test fun inheritedPrivateFieldsCanBeUpdated() {
        val target = Child()
        val field = NotificationMembers.field(Child::class.java, "importance", Integer.TYPE)
        assertEquals(3, field.getInt(target))
        field.setInt(target, 4)
        assertEquals(4, field.getInt(target))
    }

    @Test fun methodResolutionDoesNotHookUnrelatedOverloadsOrDuplicateOverrides() {
        val methods = NotificationMembers.methods(Child::class.java, "attach") {
            it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(String::class.java))
        }
        assertEquals(1, methods.size)
        assertEquals(Child::class.java, methods.single().declaringClass)
    }

    @Test(expected = IllegalArgumentException::class)
    fun changedFieldTypeIsRejectedBeforeHookInstallation() {
        NotificationMembers.field(Child::class.java, "importance", java.lang.Boolean.TYPE)
    }

    @Test fun erasedSyntheticMapperSignatureCanBeResolved() {
        val method = NotificationMembers.method(SpecializedMapper::class.java, "invoke", Any::class.java, Any::class.java)
        assertEquals(SpecializedMapper::class.java, method.declaringClass)
        assertTrue(method.isBridge)
        assertEquals("notification", method.invoke(SpecializedMapper(), "notification"))
    }

    @Test(expected = IllegalStateException::class)
    fun missingCompatibleMethodFailsInsteadOfReportingSuccess() {
        NotificationMembers.method(Child::class.java, "attach", Void.TYPE, Any::class.java)
    }

    @Test fun importanceListenerPersistsValidChoicesAndRejectsInvalidInput() {
        val choices = mutableListOf<Int>()
        val handler = ImportanceListener { choices.add(it); true }
        val listener = Proxy.newProxyInstance(ChangeListener::class.java.classLoader,
            arrayOf(ChangeListener::class.java), handler) as ChangeListener
        assertTrue(listener.onPreferenceChange(null, "0"))
        assertTrue(listener.onPreferenceChange(null, "4"))
        assertTrue(listener.onPreferenceChange(null, "5"))
        listOf(null, "", "-1000", "6", "loud", "3.5").forEach {
            assertFalse(listener.onPreferenceChange(null, it))
        }
        assertEquals(listOf(0, 4, 5), choices)
        assertEquals(listener, listener)
        assertFalse(listener.equals(Any()))
        assertEquals(System.identityHashCode(listener), listener.hashCode())
        assertNotNull(listener.toString())
    }

    @Test fun failedPersistenceIsReportedToSettings() {
        val listener = Proxy.newProxyInstance(ChangeListener::class.java.classLoader,
            arrayOf(ChangeListener::class.java), ImportanceListener { false }) as ChangeListener
        assertFalse(listener.onPreferenceChange(null, "4"))
    }

    @Test fun minimumImportanceIconsAreHiddenWithoutAffectingTheBypassBranch() {
        assertTrue(NotificationPolicy.suppressIcon(false, 0))
        assertTrue(NotificationPolicy.suppressIcon(false, 1))
        assertFalse(NotificationPolicy.suppressIcon(false, 2))
        assertFalse(NotificationPolicy.suppressIcon(false, 4))
        assertFalse(NotificationPolicy.suppressIcon(true, 1))
    }

    @Test fun scopeRequirementsCoverEveryProcess() {
        assertEquals(setOf(NotificationKeys.SYSTEM_UI, NotificationKeys.SETTINGS, NotificationKeys.SYSTEM),
            NotificationKeys.targets(NotificationKeys.ALLOW_EDIT).toSet())
        assertEquals(listOf(NotificationKeys.SYSTEM_UI, NotificationKeys.SETTINGS),
            NotificationKeys.targets(NotificationKeys.IMPORTANCE))
        assertEquals(8, NotificationKeys.ALL.distinct().size)
    }
}
