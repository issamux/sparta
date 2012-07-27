package sparta.checkers;

import java.io.StringWriter;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import com.google.gson.Gson;


/* Data classes that contain the information that will
 * be output as JSON.
 * For each report kind we introduce a separate Data subtype.
 *
 * The "datakind" field should be defined in every class to
 * distinguish what kind of node it is.
 * The "datakind" field must not be static, as it would not be
 * shown in JSON then.
 */
abstract class Data {
    String filename;
    long line; 
}
class ReadWriteData extends Data {
    final String datakind = ReadWriteData.class.getCanonicalName();
    String part;
}
class WriteData extends Data {
    final String datakind = WriteData.class.getCanonicalName();
    String part;
}
class CallData extends Data {
    final String datakind = CallData.class.getCanonicalName();
    String part;
}
class NewData extends Data {
    final String datakind = NewData.class.getCanonicalName();
    String part;
}
class InheritData extends Data {
    final String datakind = InheritData.class.getCanonicalName();
    String part;
}
class OverrideData extends Data {
    final String datakind = OverrideData.class.getCanonicalName();
    String part;
}
class UseData extends Data {
    final String datakind = UseData.class.getCanonicalName();
    String useof;
    String useofkind;
    String useby;
    String usebykind;
}

/**
 * This tool converts the diagnostic messages of the {@link AndroidReportChecker}
 * into first JSON representation.
 * The Java source files to compile are given as only command-line arguments.
 *
 * @author wmdietl
 */
public class JsonJavac {
    public static void main(String[] args) {
        String cpath = System.getenv("CLASSPATH");

        // TODO: Make configurable by Checker, reuse as much as possible.
        String[] compArgs = new String[] {"-Xbootclasspath/p:" + cpath,
                "-processor", "sparta.checkers.AndroidReportChecker",
                "-proc:only", // don't compile classes to save time
                "-encoding", "ISO8859-1", // TODO: needed for JabRef only, make optional
                "-Xmaxwarns", "100000",
                "-AprintErrorStack",
                "-Awarns"};

        // Non-diagnostic compiler output will end up here.
        StringWriter javacoutput = new StringWriter();
        // The nuggets: the diagnostic messages from the compiler.
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        Iterable<? extends JavaFileObject> files = fileManager.getJavaFileObjectsFromStrings(Arrays.asList(args));

        JavaCompiler.CompilationTask task = compiler.getTask(javacoutput, fileManager,
                  diagnostics, Arrays.asList(compArgs), null, files);

        // Run the compiler.
        task.call();

        // Filter the output and print the JSON results.
        filterAndPrint(diagnostics.getDiagnostics());

        // Just in case there are other messages. Put into error stream to allow separation.
        if (!javacoutput.toString().isEmpty()) {
            System.err.println("javac output: " + javacoutput);
        }
    }

    /**
     * Go through the provided diagnostics, parse the message, and create
     * the JSON output.
     *
     * @param diagnostics
     */
    private static void filterAndPrint(
            List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        List<Data> datas = new LinkedList<Data>();

        for (Diagnostic<? extends JavaFileObject> diag : diagnostics) {
            // System.out.println("Looking at: " + diag);

            String msg = diag.getMessage(null);
            Data data = null;
            String key = msg.substring(0, msg.indexOf(' '));

            switch (key) {
            case "READWRITE": {
                Matcher match = READWRITE_Pattern.matcher(msg);
                if (match.matches()) {
                    data = new ReadWriteData();
                    ((ReadWriteData)data).part = match.group(1);
                }
                break;
            }
            case "WRITE": {
                Matcher match = WRITE_Pattern.matcher(msg);
                if (match.matches()) {
                    data = new WriteData();
                    ((WriteData)data).part = match.group(1);
                }
                break;
            }
            case "CALL": {
                Matcher match = CALL_Pattern.matcher(msg);
                if (match.matches()) {
                    data = new CallData();
                    ((CallData)data).part = match.group(1);
                }
                break;
            }
            case "NEW": {
                Matcher match = NEW_Pattern.matcher(msg);
                if (match.matches()) {
                    data = new NewData();
                    ((NewData)data).part = match.group(1);
                }
                break;
            }
            case "INHERIT": {
                Matcher match = INHERIT_Pattern.matcher(msg);
                if (match.matches()) {
                    data = new InheritData();
                    ((InheritData)data).part = match.group(1);
                }
                break;
            }
            case "OVERRIDE": {
                Matcher match = OVERRIDE_Pattern.matcher(msg);
                if (match.matches()) {
                    data = new OverrideData();
                    ((OverrideData)data).part = match.group(1);
                }
                break;
            }
            case "USEOF": {
                Matcher match = USE_Pattern.matcher(msg);
                if (match.matches()) {
                    UseData usedata = new UseData();
                    usedata.useof = match.group(1);
                    usedata.useofkind = match.group(2);
                    usedata.useby = match.group(3);
                    usedata.usebykind = match.group(4);
                    data = usedata;
                }
                break;
            }
            }

            if (data!=null) {
                data.filename = diag.getSource().getName();
                data.line = diag.getLineNumber();
                datas.add(data);
            }
        }

        Gson gson = new Gson();
        String json = gson.toJson(datas);
        System.out.println(json);
    }

    /*
     * The following fields are the regular expression strings and their respecitve
     * compiled patterns for the diagnostic messages.
     * Also see file json-report-messages.properties for the strings.
     */
    private final static String READWRITE_String = "READWRITE (.*)";
    private final static Pattern READWRITE_Pattern = Pattern.compile(READWRITE_String);

    private final static String WRITE_String = "WRITE (.*)";
    private final static Pattern WRITE_Pattern = Pattern.compile(WRITE_String);

    private final static String CALL_String = "CALL (.*)";
    private final static Pattern CALL_Pattern = Pattern.compile(CALL_String);

    private final static String NEW_String = "NEW (.*)";
    private final static Pattern NEW_Pattern = Pattern.compile(NEW_String);

    private final static String INHERIT_String = "INHERIT (.*)";
    private final static Pattern INHERIT_Pattern = Pattern.compile(INHERIT_String);

    private final static String OVERRIDE_String = "OVERRIDE (.*)";
    private final static Pattern OVERRIDE_Pattern = Pattern.compile(OVERRIDE_String);

    private final static String USE_String = "USEOF (.*) OFKIND (.*) USEBY (.*) BYKIND (.*)";
    private final static Pattern USE_Pattern = Pattern.compile(USE_String);

}