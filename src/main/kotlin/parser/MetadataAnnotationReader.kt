package parser

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes

/**
 * Reads the `@kotlin.Metadata` annotation directly from `.class` bytes using ASM,
 * without loading the class. Lets us parse an arbitrary stdlib version's jar that is
 * not on the running classpath (Class.forName only resolves classpath classes).
 */
object MetadataAnnotationReader {


    fun read(classBytes: ByteArray): Metadata? {
        val collector = MetadataCollector()
        ClassReader(classBytes).accept(
            collector,
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        if (!collector.found) return null
        return Metadata(
            kind = collector.kind,
            metadataVersion = collector.mv.toIntArray(),
            data1 = collector.d1.toTypedArray(),
            data2 = collector.d2.toTypedArray(),
            extraString = collector.xs,
            packageName = collector.pn,
            extraInt = collector.xi,
        )
    }

    private class MetadataCollector : ClassVisitor(Opcodes.ASM9) {
        var found = false
        var kind = 1
        val mv = mutableListOf<Int>()
        val d1 = mutableListOf<String>()
        val d2 = mutableListOf<String>()
        var xs = ""
        var pn = ""
        var xi = 0

        override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
            if (descriptor != "Lkotlin/Metadata;") return null
            found = true
            return object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visit(name: String?, value: Any?) {
                    when (name) {
                        "k" -> kind = value as Int
                        "xs" -> xs = value as String
                        "pn" -> pn = value as String
                        "xi" -> xi = value as Int
                        // ASM delivers primitive arrays (int[]) as a single scalar value.
                        "mv" -> (value as IntArray).forEach { mv.add(it) }
                    }
                }

                override fun visitArray(name: String?): AnnotationVisitor =
                    object : AnnotationVisitor(Opcodes.ASM9) {
                        override fun visit(n: String?, value: Any?) {
                            when (name) {
                                "d1" -> d1.add(value as String)
                                "d2" -> d2.add(value as String)
                            }
                        }
                    }
            }
        }
    }
}
