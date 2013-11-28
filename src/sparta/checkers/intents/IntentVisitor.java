package sparta.checkers.intents;

/*>>>
 import checkers.compilermsgs.quals.*;
 */

import checkers.basetype.BaseTypeChecker;
import checkers.source.Result;
import checkers.types.AnnotatedTypeMirror;
import checkers.types.AnnotatedTypeMirror.AnnotatedExecutableType;

import javacutils.AnnotationUtils;
import javacutils.Pair;
import javacutils.TreeUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

import sparta.checkers.FlowAnnotatedTypeFactory;
import sparta.checkers.FlowVisitor;
import sparta.checkers.quals.FlowPermission;
import sparta.checkers.quals.IExtra;
import sparta.checkers.quals.IntentExtras;
import sparta.checkers.quals.Sink;
import sparta.checkers.quals.Source;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.api.JavacScope;
import com.sun.tools.javac.code.Attribute.Compound;
import com.sun.tools.javac.code.Attribute.TypeCompound;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.TypeVariableSymbol;
import com.sun.tools.javac.comp.AttrContext;
import com.sun.tools.javac.comp.Env;
import com.sun.tools.javac.comp.Resolve;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;

public class IntentVisitor extends FlowVisitor {

    public IntentVisitor(BaseTypeChecker checker) {
        super(checker);
    }
    @Override
    protected FlowAnnotatedTypeFactory createTypeFactory() {
        try{
            return new IntentAnnotatedTypeFactory(checker);
        }catch (Exception e){
            //The SourceVisitor cuts off the stack trace, 
            //so print it here and the throw again.
            e.printStackTrace();
            throw e;
        }
    }

    @Override
    protected void checkMethodInvocability(AnnotatedExecutableType method,
            MethodInvocationTree node) {
        // TODO: this string receiverClassName is temporary.
        // Something similar to the correct solution is commented below
        String receiverClassName = method.getReceiverType().getUnderlyingType()
            .asElement().getSimpleName().toString();
        // CHECK IF SUBTYPE of Intent, instead of equals Intent.
        // The code below does not work because getClass() returns a Symbol, and
        // not the actual class.
        // Need to find out a way to get the actual class from an Element
        // if (method.getReceiverType().getUnderlyingType()
        // .asElement().getClass().isAssignableFrom(android.content.Intent.class))
        // {

        if (receiverClassName.equals("Intent")) {
            checkIntentExtraMethods(method, node);
            return;
        }
        // if (method.getReceiverType().getUnderlyingType()
        // .asElement().getClass().isAssignableFrom(android.app.Activity.class))
        // {
        if (receiverClassName.equals("Activity") || receiverClassName.equals("ContextWrapper")) {
            checkSendIntent(method, node);
            return;
        }
        super.checkMethodInvocability(method, node);
    }

    /**
     * Method used to type-check any sendIntent call. Ex. <code>startActivity</code>,
     * <code>startService</code>, <code>sendBroadcast</code>, etc...
     * Currently only <code>startActivity</code> is implemented
     * @param method AnnotatedExecutableType
     * @param node MethodInvocationTree
     */
    private void checkSendIntent(AnnotatedExecutableType method,
            MethodInvocationTree node) {
        // TODO: Find a better way to do that other than using the method name
        String methodName = method.getElement().getSimpleName().toString();
        if (methodName.equals("startActivity")) {
            checkStartActivity(method, node);
        } else if(methodName.equals("startService")) {
            checkStartService(method,node);
        } else if(methodName.equals("sendBroadcast")) {
            checkBroadcastIntent(method, node);
        }
    }
    
    /**
     * Method used to type-check a <code>startActivity</code> call
     * @param method
     * @param node
     */

    private void checkStartActivity(AnnotatedExecutableType method,
            MethodInvocationTree node) {
        if (node.getArguments().isEmpty()) {
            // Still need to figure out a better way to treat that. 
            // Defensive programming.
            throw new RuntimeException();
        }
        ExpressionTree intentObject = node.getArguments().get(0);
        AnnotatedTypeMirror rhs = atypeFactory.getAnnotatedType(intentObject);
        AnnotationMirror rhsIntentExtras = rhs.getAnnotation(IntentExtras.class);

        for (MethodInvocationTree getIntentCall : getReceiversMethods(node,IntentUtils.GET_INTENT)) {
            AnnotatedTypeMirror lhs = atypeFactory.getAnnotatedType(getIntentCall);
            AnnotationMirror lhsIntentExtras = lhs.getAnnotation(IntentExtras.class);
            if (!isCopyableTo(rhsIntentExtras, lhsIntentExtras)) {
                checker.report(Result.failure("send.intent"), node);
            }
        }
    }
    
    /**
     * Method used to type-check a <code>startService</code> call
     * @param method
     * @param node
     */

    private void checkStartService(AnnotatedExecutableType method,
            MethodInvocationTree node) {
        if (node.getArguments().isEmpty()) {
            // Still need to figure out a better way to treat that. 
            // Defensive programming.
            throw new RuntimeException();
        }
        ExpressionTree intentObject = node.getArguments().get(0);
        AnnotatedTypeMirror rhs = atypeFactory.getAnnotatedType(intentObject);
        AnnotationMirror rhsIntentExtras = rhs.getAnnotation(IntentExtras.class);

        for (MethodInvocationTree callBackMethod : getReceiversMethods(node,IntentUtils
                .SERVICE_CALLBACK_METHODS)) {
            Pair<AnnotatedExecutableType, List<AnnotatedTypeMirror>> realCallBackMethod = atypeFactory.methodFromUse(callBackMethod);
            AnnotatedTypeMirror lhs = realCallBackMethod.first.getParameterTypes().get(0);
            AnnotationMirror lhsIntentExtras = lhs.getAnnotation(IntentExtras.class);
            if (!isCopyableTo(rhsIntentExtras, lhsIntentExtras)) {
                checker.report(Result.failure("send.intent"), node);
            }
        }
    }
    
    /**
     * Method used to type-check a <code>sendBroadcast</code> call
     * @param method
     * @param node
     */
    
    private void checkBroadcastIntent(AnnotatedExecutableType method,
            MethodInvocationTree node) {
        if (node.getArguments().isEmpty()) {
            // Still need to figure out a better way to treat that. 
            // Defensive programming.
            throw new RuntimeException();
        }
        ExpressionTree intentObject = node.getArguments().get(0);
        AnnotatedTypeMirror rhs = atypeFactory.getAnnotatedType(intentObject);
        AnnotationMirror rhsIntentExtras = rhs.getAnnotation(IntentExtras.class);

        for (MethodInvocationTree callBackMethod : getReceiversMethods(node,IntentUtils.BRECEIVER_CALLBACK_METHODS)) {
            Pair<AnnotatedExecutableType, List<AnnotatedTypeMirror>> realCallBackMethod = atypeFactory.methodFromUse(callBackMethod);
            AnnotatedTypeMirror lhs = realCallBackMethod.first.getParameterTypes().get(1);
            AnnotationMirror lhsIntentExtras = lhs.getAnnotation(IntentExtras.class);
            if (!isCopyableTo(rhsIntentExtras, lhsIntentExtras)) {
                checker.report(Result.failure("send.intent"), node);
            }
        }
    }


    /**
     * Get MethodSymbol based on class name, method name, and parameter length.
     * Note: This code was reused from the Reflection analysis.
     * @param className is the name of the receiver component
     * @param methodName is always getIntent() for now, but later will be used for other callback methods. 
     * @return the corresponding method symbol or <code>null</code> if the
     *         method is not unique or cannot be determined
     */
    private Symbol getMethodSymbolFor(String className, String methodName,
            int paramLength, Env<AttrContext> env) {
        Context context = ((JavacProcessingEnvironment) checker
            .getProcessingEnvironment()).getContext();
        Resolve resolve = Resolve.instance(context);
        Names names = Names.instance(context);

        Symbol result = null;
        Method loadClass;
        try {
            loadClass = Resolve.class.getDeclaredMethod("loadClass", Env.class,Name.class);
            loadClass.setAccessible(true);
            Symbol symClass;
            symClass = (Symbol) loadClass.invoke(resolve, env, names.fromString(className));
            if (!symClass.exists()) {
                return result;
            }

            // TODO: We have to iterate over all super classes as the method(s)
            // we are looking for might be declared there
            for (Symbol s : symClass.getEnclosedElements()) {
                // Check all member methods
                if (s.getKind() == ElementKind.METHOD) {
                    // Check for method name and number of arguments
                    if (names.fromString(methodName).equals(s.name)
                            && ((MethodSymbol) s).getParameters().size() == paramLength) {
                        if (result != null) {
                            // Method name and parameter length is not unique in
                            // given class
                            result = null;
                            break;// outer;
                        }
                        result = s;
                    }
                }
            }
        } catch (IllegalAccessException | IllegalArgumentException
                | NoSuchMethodException | SecurityException
                | InvocationTargetException e) {
            e.printStackTrace();
        }
        return result;
    }

    /**
     * This method is responsible for obtaining the list of all receivers
     * methods from all possible receivers from a certain sendIntent() call.
     * getIntent() method for Activities, and callback methods for Services
     * and BroadcastReceivers.
     * 
     * @param tree
     *            the Tree of the sendIntent() call
     * @return A list of getIntent() methods
     */

    
    private List<MethodInvocationTree> getReceiversMethods(
            MethodInvocationTree tree, List<String> methodNames) {

        JCMethodInvocation methodInvocation = (JCMethodInvocation) tree;
        Context context = ((JavacProcessingEnvironment) checker
            .getProcessingEnvironment()).getContext();
        // Return the sender intent in the String format. Check method @Javadoc.
        String senderString = IntentUtils.resolveIntentFilters(tree, atypeFactory, 
            getCurrentPath());
        // Getting the receivers components in the String format from the 
        // Component map
        IntentAnnotatedTypeFactory intentAnnotatedTypeFactory = 
        (IntentAnnotatedTypeFactory)atypeFactory;
        Set<String> receiversStrList = intentAnnotatedTypeFactory.
        getReceiversFromSender(senderString);
        List<MethodInvocationTree> receiversList = new ArrayList<MethodInvocationTree>();
        // Here, we have the intent receivers set in the String format, and we
        // need to obtain
        // a list of it in the MethodInvocationTree format. The code to do that
        // is below.
        // TODO: Most of the code below is reused from the Reflection checker.
        // Refactor this so that both the Intent checker and the Intent checker
        // use the same code.

        // make is used to select the getIntent() method on the loop below.
        TreeMaker make = TreeMaker.instance(context);
        TreePath path = atypeFactory.getPath(tree);
        JavacScope scope = (JavacScope) trees.getScope(path);
        Env<AttrContext> env = scope.getEnv();
        // Get receiver, which is always the first argument of the invoke method
        JCExpression receiver = methodInvocation.args.head;
        // The remaining list contains the arguments
        com.sun.tools.javac.util.List<JCExpression> args = methodInvocation.args.tail;

        // Resolve the Symbol for the current method
        for (String receiverStr : receiversStrList) {
            for(String method : methodNames) {
                String[] methodAndParamLength = method.split(",");
                if(methodAndParamLength.length != 2) {
                    throw new RuntimeException("Method not in NAME,#Param format");
                }
                String methodName = methodAndParamLength[0];
                int paramLength = Integer.parseInt(methodAndParamLength[1]);
                Symbol symbol = getMethodSymbolFor(receiverStr, methodName,
                        paramLength, env);
                if (symbol != null) {
                    JCExpression newMethod = make.Select(receiver, symbol);
                    // Build method invocation tree depending on the number of
                    // parameters
                    JCMethodInvocation syntTree = paramLength > 0 ? make.App(newMethod, args) //TODO: PAULO: Sub args for real args from onBind
                            : make.App(newMethod);
                    // add method invocation tree to the list of possible methods
                    receiversList.add(syntTree);
                } else if(methodName.equals("getIntent")) {
                    checker.report(Result.failure("getintent.not.found", receiverStr), tree);
                }
            }
        }
        return receiversList;
    }

    /**
     * This method is called every time we are visiting a method from the Intent
     * class. If the method being visited is a putExtra or getExtra call, we
     * type check it.
     * 
     * @param method
     *            Annotations on the method
     * @param node
     *            The tree of the method call
     */

    private void checkIntentExtraMethods(AnnotatedExecutableType method,
            MethodInvocationTree node) {
        if (node.getArguments().isEmpty()) {
            // If gets here, its not a getExtra nor putExtra.
            return;
        }
        // The first element from putExtra and getExtra calls are keys.
        String keyName = node.getArguments().get(0).toString();
        // Removing the "" from the key. "key" -> key
        keyName = keyName.substring(1, keyName.length() - 1);
        ExpressionTree receiver = TreeUtils.getReceiverTree(node);
        AnnotatedTypeMirror receiverType = atypeFactory.getAnnotatedType(receiver);
        if (IntentUtils.isPutExtraMethod(node)) {
            checkPutExtra(node, keyName, receiver, receiverType);
        } else if (IntentUtils.isGetExtraMethod(node, checker.getProcessingEnvironment())) {
            checkGetExtra(method, node, keyName, receiver, receiverType);
        }
    }

    /**
     * Method used to type-check a <code>intent.getExtra(...)</code> method call. Here we just
     * need to check if the key can be found in the @IntentExtras from <code>intent</code>.
     * The modification of the return type of this method is made in the IntentAnnotatedTypeFactory.
     * @param method
     *            Actual getExtra method
     * @param node
     *            Tree of the getExtra() method call, used only to show where a
     *            warning is being raised, in case it is.
     * @param keyName
     *            The key parameter of the getExtra(...) call. Ex:
     *            getExtra("key").
     * @param receiver
     *            Receiver component name in the String format. Ex: "ActivityA"
     * @param receiverType
     *            Annotations of the receiver calling intent.getExtra(...).
     */

    private void checkGetExtra(AnnotatedExecutableType method,
            MethodInvocationTree node, String keyName, ExpressionTree receiver,
            AnnotatedTypeMirror receiverType) {
        if (receiverType.hasAnnotation(IntentExtras.class)) {
            AnnotationMirror receiverIntentAnnotation = receiverType
                .getAnnotation(IntentExtras.class);
            if(!IntentUtils.hasKey(receiverIntentAnnotation, keyName)) {
                // If key could not be found in the @IntentExtras, raise a warning.
                checker.report(Result.failure("intent.key.notfound", keyName, 
                    receiver.toString()), node);
            }
        }
    }

    /**
     * Method to type check the putExtra call. In every putExtra("key",value)
     * call the "key" needs to be found in one of the @IExtra elements from the @IntentExtras
     * of the intent, and the @Source and @Sink from the @IExtra need to be a
     * supertype of the @Source and @Sink of value. TODO In the future, we want
     * to add inference to the putExtra() call. If the key cannot be found in
     * the @IntentExtras annotation, intent.putExtra("key",value) should update
     * the intent's @IntentExtra annotation adding a new @IExtra with key "key"
     * and @Source and @Sink of value.
     * 
     * @param node
     *            The tree of the putExtra method call
     * @param keyName
     *            The key of the element being added to the intent bundle
     * @param receiver
     *            Receiver component name in the String format. Ex: ActivityA
     * @param receiverType
     *            Annotations of the receiver calling intent.putExtra(...).
     */
    private void checkPutExtra(MethodInvocationTree node, String keyName, ExpressionTree receiver,
            AnnotatedTypeMirror receiverType) {
        AnnotationMirror iExtra = IntentUtils.getIExtra(keyName, receiverType);
        if (iExtra == null) {
            checker.report(Result.failure("intent.key.notfound", keyName, receiver.toString()),
                    node);
            return;
        }

        // Getting annotations of the value being added to the intent
        // bundle.
        AnnotatedTypeMirror valueType = atypeFactory.getAnnotatedType(node.getArguments().get(1));
        AnnotatedTypeMirror declaredKeyType = hostGetTypeOfKey(iExtra,
                valueType.getUnderlyingType());

        // Check if the value being added to the intent bundle is a
        // subtype
        // of what the @IntentExtras annotation contains.
        if (!atypeFactory.getTypeHierarchy().isSubtype(valueType, declaredKeyType)) {
            checker.report(Result.failure("intent.type.incompatible", receiver.toString(),
                    keyName, declaredKeyType.getAnnotation(Source.class),
                    declaredKeyType.getAnnotation(Sink.class),
                    valueType.getAnnotation(Source.class), valueType.getAnnotation(Sink.class)),
                    node);
        }
        // TODO: Inference on putExtra
        // If key couldn't be found, add it to the @IntentExtras with the
        // flow of the object being inserted on the hashmap.
        // if(!found) {
        // IntentChecker intentChecker = (IntentChecker) checker;
        // AnnotationMirror newIExtra =
        // intentChecker.createIExtraAnno(keyName,
        // valueType.getAnnotation(Source.class),
        // valueType.getAnnotation(Sink.class));
        // AnnotationMirror newIntentExtras =
        // intentChecker.addIExtraToIntentExtras(receiverIntentAnnotation,
        // newIExtra);
        // receiverType.replaceAnnotation(newIntentExtras);
        // System.out.println();
        // }

    }




    private AnnotatedTypeMirror hostGetTypeOfKey(AnnotationMirror iExtra, TypeMirror javaType) {
        Set<FlowPermission> annotatedSources = new HashSet<FlowPermission>(
            AnnotationUtils.getElementValueEnumArray(iExtra,
                "source", FlowPermission.class, true));
        Set<FlowPermission> annotatedSinks = new HashSet<FlowPermission>(
            AnnotationUtils.getElementValueEnumArray(iExtra,
                "sink", FlowPermission.class, true));

        // Creating a type with the @Source and @Sink from value in
        // putExtra(key,value).
        AnnotationMirror sourceAnnotation = atypeFactory
            .createAnnoFromSource(annotatedSources);
        AnnotationMirror sinkAnnotation = atypeFactory
            .createAnnoFromSink(annotatedSinks);
        

        // annotatedType is a dummy type containing the @Source and
        // @Sink
        // from the IExtra, only to be used in the isSubType() call.
        AnnotatedTypeMirror annotatedType = AnnotatedTypeMirror
            .createType(javaType, atypeFactory);

        annotatedType.addAnnotation(sourceAnnotation);
        annotatedType.addAnnotation(sinkAnnotation);
        annotatedType.addAnnotation(((IntentAnnotatedTypeFactory) atypeFactory)
            .EMPTYINTENTEXTRAS);
        return annotatedType;
    }
    

    @Override
    public Void visitAnnotation(AnnotationTree node, Void p) {
        List<? extends ExpressionTree> args = node.getArguments();
        if (args.isEmpty()) {
            // Nothing to do if there are no annotation arguments.
            return null;
        }

        Element anno = TreeInfo.symbol((JCTree) node.getAnnotationType());
        if (anno.toString().equals(IntentExtras.class.getName())
                || anno.toString().equals(IExtra.class.getName())) {
            return null;
        }
        return super.visitAnnotation(node, p);
    }

    /**
     * This method receives annotations of 2 Intents and returns true if rhs can
     * be sent to lsh. For that to happen, every key in lhs need to exists in
     * rhs, and the @Source and @Sink with that key in rhs needs to be a subtype
     * of the
     * 
     * @Source and @Sink with that same key in lhs.
     * @param rhs
     *            Sender intent annotations
     * @param lhs
     *            Receiver intent annotations
     * @return true if the intent with annotations rhs can be sent to the intent
     *         with annotations lhs.
     */

    public boolean isCopyableTo(AnnotationMirror rhs, AnnotationMirror lhs) {
        if (rhs == null || lhs == null) {
            return false;
        }
        List<AnnotationMirror> rhsIExtrasList = AnnotationUtils
            .getElementValueArray(rhs, "value", AnnotationMirror.class, true);
        List<AnnotationMirror> lhsIExtrasList = AnnotationUtils
            .getElementValueArray(lhs, "value", AnnotationMirror.class, true);
        if (lhsIExtrasList.isEmpty()) {
            return true;
        }

        // Iterating on the @IExtra from lhs
        for (AnnotationMirror lhsIExtra : lhsIExtrasList) {
            boolean found = false;
            String leftKey = AnnotationUtils.getElementValue(lhsIExtra, "key",
                String.class, true);
            for (AnnotationMirror rhsIExtra : rhsIExtrasList) {
                String rightKey = AnnotationUtils.getElementValue(rhsIExtra,
                    "key", String.class, true);
                if (rightKey.equals(leftKey)) {
                    // Found 2 @IExtra with same keys in rhs and lhs.
                    // Now we need to make sure that @Source and @Sink of
                    // the @IExtra in rhs are subtypes of @Source and @Sink
                    // of @IExtra in lhs.
                    found = true;
                    if (!hostIsCopyableTo(lhsIExtra,rhsIExtra)) {
                        return false;
                    } else {
                        // if it is a subtype, exit the inner loop and
                        // check another @IExtra in lhs
                        break;
                    }
                }
            }
            if (!found) {
                // If key is missing in the rhs, return false
                return false;
            }
        }
        return true;
    }
    
    
    
    /**
     * temporary auxiliar method used to type-check the 
     * isCopyableTo rule for information flow analysis 
     * on intents.
     * @param lhsIExtra
     * @param rhsIExtra
     * @return
     */
    
    private boolean hostIsCopyableTo(AnnotationMirror lhsIExtra, 
            AnnotationMirror rhsIExtra) {
        Set<FlowPermission> lhsAnnotatedSources = new HashSet<FlowPermission>(
            AnnotationUtils.getElementValueEnumArray(lhsIExtra,
                "source", FlowPermission.class, true));
        Set<FlowPermission> lhsAnnotatedSinks = new HashSet<FlowPermission>(
            AnnotationUtils.getElementValueEnumArray(lhsIExtra,
                "sink", FlowPermission.class, true));
        Set<FlowPermission> rhsAnnotatedSources = new HashSet<FlowPermission>(
            AnnotationUtils.getElementValueEnumArray(rhsIExtra,
                "source", FlowPermission.class, true));
        Set<FlowPermission> rhsAnnotatedSinks = new HashSet<FlowPermission>(
            AnnotationUtils.getElementValueEnumArray(rhsIExtra,
                "sink", FlowPermission.class, true));

        //Creating dummy type to add annotations to it and check if isSubtype
        TypeMirror dummy = atypeFactory.getProcessingEnv().getTypeUtils()
            .getPrimitiveType(TypeKind.BOOLEAN);
        AnnotatedTypeMirror lhsAnnotatedType = AnnotatedTypeMirror
            .createType(dummy, atypeFactory);
        AnnotatedTypeMirror rhsAnnotatedType = AnnotatedTypeMirror
            .createType(dummy, atypeFactory);

        AnnotationMirror lhsSourceAnnotation = atypeFactory.createAnnoFromSource(lhsAnnotatedSources);
        AnnotationMirror lhsSinkAnnotation = atypeFactory.createAnnoFromSink(lhsAnnotatedSinks);
        AnnotationMirror rhsSourceAnnotation = atypeFactory.createAnnoFromSource(rhsAnnotatedSources);
        AnnotationMirror rhsSinkAnnotation = atypeFactory.createAnnoFromSink(rhsAnnotatedSinks);

        lhsAnnotatedType.addAnnotation(lhsSourceAnnotation);
        lhsAnnotatedType.addAnnotation(lhsSinkAnnotation);
        rhsAnnotatedType.addAnnotation(rhsSourceAnnotation);
        rhsAnnotatedType.addAnnotation(rhsSinkAnnotation);

        //Why is this necessary? 
        //How to set atypeFactory to IntentAnnotatedTypeFactory instead of FlowAnnotatedTypeFactory?
        IntentAnnotatedTypeFactory intentAnnotatedTypeFactory = 
            (IntentAnnotatedTypeFactory) atypeFactory;
        lhsAnnotatedType.addAnnotation(intentAnnotatedTypeFactory.EMPTYINTENTEXTRAS);
        rhsAnnotatedType.addAnnotation(intentAnnotatedTypeFactory.EMPTYINTENTEXTRAS);
        return atypeFactory.getTypeHierarchy().isSubtype(rhsAnnotatedType,lhsAnnotatedType);
    }

}