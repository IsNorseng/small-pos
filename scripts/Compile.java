import javax.tools.*;
import java.util.*;

/** One short-lived compiler process owns its file manager until process exit. */
public final class Compile {
    public static void main(String[] args) {
        int separator=Arrays.asList(args).indexOf("--");
        if(separator<0)throw new IllegalArgumentException("Compiler options -- source files");
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager files=compiler.getStandardFileManager(null,null,java.nio.charset.StandardCharsets.UTF_8);
        Iterable<? extends JavaFileObject> sources=files.getJavaFileObjectsFromStrings(Arrays.asList(args).subList(separator+1,args.length));
        boolean ok=compiler.getTask(null,files,null,Arrays.asList(args).subList(0,separator),null,sources).call();
        // Do not close the JDK ZipFS here: restricted Windows environments can
        // deny its final real-path lookup. The OS closes all handles on exit.
        System.exit(ok?0:1);
    }
}
