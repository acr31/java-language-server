package org.javacs;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import java.util.Optional;

public class TestMethod {
    public final JavacTask parseTask;
    public final CompilationUnitTree compilationUnit;
    public final ClassTree enclosingClass;
    public final Optional<MethodTree> method;

    TestMethod(
            JavacTask parseTask,
            CompilationUnitTree compilationUnit,
            ClassTree enclosingClass,
            Optional<MethodTree> method) {
        this.parseTask = parseTask;
        this.compilationUnit = compilationUnit;
        this.enclosingClass = enclosingClass;
        this.method = method;
    }
}
