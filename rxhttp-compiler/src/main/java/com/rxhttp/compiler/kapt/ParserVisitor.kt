package com.rxhttp.compiler.kapt

import com.rxhttp.compiler.isDependenceRxJava
import com.rxhttp.compiler.rxhttpClass
import com.squareup.javapoet.AnnotationSpec
import com.squareup.javapoet.ArrayTypeName
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.ParameterSpec
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeVariableName
import rxhttp.wrapper.annotation.Parser
import java.util.*
import javax.annotation.processing.Filer
import javax.annotation.processing.Messager
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement
import javax.lang.model.element.VariableElement
import javax.lang.model.type.MirroredTypesException
import javax.lang.model.util.Types

class ParserVisitor(private val logger: Messager) {

    private val elementMap = LinkedHashMap<String, TypeElement>()
    private val typeMap = LinkedHashMap<String, List<ClassName>>()

    fun add(element: TypeElement, types: Types) {
        try {
            element.checkParserValidClass(types)
            val annotation = element.getAnnotation(Parser::class.java)
            val name: String = annotation.name
            if (name.isBlank()) {
                val msg = "methodName() in @${Parser::class.java.simpleName} for class " +
                        "${element.qualifiedName} is null or empty! that's not allowed"
                throw NoSuchElementException(msg)
            }
            try {
                annotation.wrappers
            } catch (e: MirroredTypesException) {
                val typeMirrors = e.typeMirrors
                typeMap[name] = typeMirrors.map { ClassName.bestGuess(it.toString()) }
            }
            elementMap[name] = element
        } catch (e: NoSuchElementException) {
            logger.error(e.message, element)
        }
    }

    fun getMethodList(filer: Filer): List<MethodSpec> {

        val methodList = ArrayList<MethodSpec>()
        val rxHttpExtensions = RxHttpExtensions()

        //获取自定义的解析器
        elementMap.forEach { (parserAlias, typeElement) ->
            //生成kotlin编写的toObservableXxx/toAwaitXxx/toFlowXxx方法
            rxHttpExtensions.generateRxHttpExtendFun(typeElement, parserAlias)
            //生成Java环境下toObservableXxx方法
            methodList.addAll(typeElement.getToObservableXxxFun(parserAlias, typeMap))
        }
        rxHttpExtensions.generateClassFile(filer)
        return methodList
    }
}

//生成Java语言编写的toObservableXxx方法
private fun TypeElement.getToObservableXxxFun(
    parserAlias: String,
    typeMap: LinkedHashMap<String, List<ClassName>>
): List<MethodSpec> {
    val methodList = ArrayList<MethodSpec>()
    //onParser方法返回类型
    val onParserFunReturnType = getOnParserFunReturnType() ?: return emptyList()
    val typeVariableNames = typeParameters.map { TypeVariableName.get(it) }
    val typeCount = typeVariableNames.size  //泛型数量
    val customParser = ClassName.get(this)

    //遍历public构造方法
    for (constructor in getPublicConstructors()) {
        //参数为空，说明该构造方法无效
        constructor.getParametersIfValid(typeCount) ?: continue

        //根据构造方法参数，获取toObservableXxx方法需要的参数
        val parameterList = constructor.getParameterSpecs(typeVariableNames)

        //方法名
        val methodName = "toObservable$parserAlias"
        //返回类型(Observable<T>类型)
        val toObservableXxxFunReturnType = rxhttpClass.peerClass("ObservableCall")
            .parameterizedBy(onParserFunReturnType)

        val types = getTypeVariableString(typeVariableNames) // <T>, <K, V> 等
        //参数名
        val paramsName = getParamsName(constructor.parameters, parameterList, typeCount)
        //方法体
        val toObservableXxxFunBody = if (typeCount == 1) {
            CodeBlock.of("return toObservable(wrap${customParser.simpleName()}($paramsName))")
        } else {
            CodeBlock.of("return toObservable(new \$T$types($paramsName))", customParser)
        }

        val varargs = constructor.isVarArgs && parameterList.last().type is ArrayTypeName

        val originParameters = constructor.parameters.map { ParameterSpec.get(it) }

        if (typeCount == 1) {
            val t = TypeVariableName.get("T")
            val typeUtil = ClassName.get("rxhttp.wrapper.utils", "TypeUtil")
            val okResponseParser = ClassName.get("rxhttp.wrapper.parse", "OkResponseParser")
            val parserClass = okResponseParser.peerClass("Parser").parameterizedBy(t)

            val suppressAnnotation = AnnotationSpec.builder(SuppressWarnings::class.java)
                .addMember("value", "\$S", "unchecked")
                .build()

            val index = paramsName.indexOf(",")

            val wrapParams = if (index != -1) ", ${paramsName.substring(index + 1)}" else ""

            MethodSpec.methodBuilder("wrap${customParser.simpleName()}")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addAnnotation(suppressAnnotation)
                .addTypeVariable(t)
                .addParameters(originParameters)
                .returns(parserClass)
                .addCode(
                    """
                    Type actualType = ${'$'}T.getActualType(type);
                    if (actualType == null) actualType = type;
                    ${'$'}T parser = new ${'$'}T(actualType$wrapParams);
                    return actualType == type ? parser : new ${'$'}T(parser);
                """.trimIndent(), typeUtil, customParser, customParser, okResponseParser
                ).build().apply { methodList.add(this) }
        }

        if (isDependenceRxJava()) {
            MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PUBLIC)
                .addTypeVariables(typeVariableNames)
                .addParameters(originParameters)
                .varargs(varargs)
                .addStatement(toObservableXxxFunBody)
                .returns(toObservableXxxFunReturnType)
                .build()
                .apply { methodList.add(this) }
        }

        if (typeCount > 0 && isDependenceRxJava()) {
            val paramNames = getParamsName(constructor.parameters, parameterList, typeCount, true)

            val methodSpec = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PUBLIC)
                .addTypeVariables(typeVariableNames)
                .addParameters(parameterList)
                .varargs(varargs)
                .addStatement("return $methodName($paramNames)")  //方法里面的表达式
                .returns(toObservableXxxFunReturnType)
                .build()
                .apply { methodList.add(this) }

            val haveClassTypeParam = parameterList.any { p -> p.type.isClassType() }

            //注意，这里获取泛型边界跟ksp不一样，这里会自动过滤Object类型，即使手动声明了
            if (haveClassTypeParam && typeCount == 1 && typeVariableNames.first().bounds.isEmpty()) {
                //有Class类型参数 且 泛型数量等于1 且没有为泛型指定边界(Object类型边界除外)，才去生成Parser注解里wrappers字段对应的toObservableXxx方法
                constructor.getToObservableXxxFun(
                    parserAlias, methodSpec, onParserFunReturnType, typeMap, methodList
                )
            }
        }
    }
    return methodList
}

//获取方法参数，如果该方法有效
fun ExecutableElement.getParametersIfValid(
    typeSize: Int
): List<VariableElement>? {
    val tempParameters = parameters
    var typeCount = typeSize  //泛型数量
    if ("java.lang.reflect.Type[]" == tempParameters.firstOrNull()?.asType()?.toString()) {
        typeCount = 1  //如果是Type是数组传递的，一个参数就行
    } else {
        //如果解析器有n个泛型，则构造方法前n个参数，必须是Type类型
        val match = tempParameters.subList(0, typeCount).all {
            "java.lang.reflect.Type" == it.asType().toString()
        }
        if (!match) return null
    }
    //构造方法参数数量小于泛型数量，直接过滤掉
    if (tempParameters.size < typeCount) return null
    return tempParameters.subList(typeCount, tempParameters.size)
}


/**
 * 生成Parser注解里wrappers字段指定类对应的toObservableXxx方法
 * @param parserAlias 解析器别名
 * @param methodSpec 解析器对应的toObservableXxx方法，没有经过wrappers字段包裹前的
 * @param onParserFunReturnType 解析器里onParser方法的返回类型
 * @param typeMap Parser注解里wrappers字段集合
 * @param methodList MethodSpecs
 */
private fun ExecutableElement.getToObservableXxxFun(
    parserAlias: String,
    methodSpec: MethodSpec,
    onParserFunReturnType: TypeName,
    typeMap: LinkedHashMap<String, List<ClassName>>,
    methodList: MutableList<MethodSpec>
) {
    val parameterList = methodSpec.parameters
    val typeVariableNames = methodSpec.typeVariables

    val type = ClassName.get("java.lang.reflect", "Type")
    val parameterizedType = ClassName.get("rxhttp.wrapper.entity", "ParameterizedTypeImpl")

    val wrapperListClass = arrayListOf<ClassName>()
    typeMap[parserAlias]?.apply { wrapperListClass.addAll(this) }
    val listClassName = ClassName.get("java.util", "List")
    if (listClassName !in wrapperListClass) {
        wrapperListClass.add(0, listClassName)
    }

    wrapperListClass.forEach { wrapperClass ->

        //1、toObservableXxx方法返回值
        val onParserFunReturnWrapperType =
            if (onParserFunReturnType is ParameterizedTypeName) {
                //返回类型有n个泛型，需要对每个泛型再次包装
                val typeNames = onParserFunReturnType.typeArguments.map { typeArg ->
                    wrapperClass.parameterizedBy(typeArg)
                }
                onParserFunReturnType.rawType.parameterizedBy(*typeNames.toTypedArray())
            } else {
                wrapperClass.parameterizedBy(onParserFunReturnType)
            }
        val toObservableXxxFunReturnType = rxhttpClass.peerClass("ObservableCall")
            .parameterizedBy(onParserFunReturnWrapperType)

        //2、toObservableXxx方法名
        val name = wrapperClass.toString()
        val simpleName = name.substring(name.lastIndexOf(".") + 1)
        val methodName = "toObservable$parserAlias${simpleName}"

        //3、toObservableXxx方法体
        val funBody = CodeBlock.builder()
        val paramsName = StringBuilder()
        //遍历参数，取出参数名
        parameterList.forEachIndexed { index, param ->
            if (index > 0) paramsName.append(", ")
            if (param.type.isClassType()) {
                /*
                 * Class类型参数，需要进行再次包装，最后再取参数名
                 * 格式：Type tTypeList = ParameterizedTypeImpl.get(List.class, tType);
                 */
                val variableName = "${param.name}$simpleName"
                val expression = "\$T $variableName = \$T.get($simpleName.class, ${param.name})"
                funBody.addStatement(expression, type, parameterizedType)
                val parameterType = parameters[index].asType()
                if ("java.lang.reflect.Type[]" == parameterType.toString()) {
                    paramsName.append("new Type[]{$variableName}")
                } else {
                    paramsName.append(variableName)
                }
            } else {
                paramsName.append(param.name)
            }
        }
        val returnStatement = "return ${methodSpec.name}($paramsName)"
        funBody.addStatement(returnStatement)

        //4、生成toObservableXxx方法
        MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariables(typeVariableNames)
            .addParameters(parameterList)
            .varargs(methodSpec.varargs)
            .addCode(funBody.build())  //方法里面的表达式
            .returns(toObservableXxxFunReturnType)
            .build()
            .apply { methodList.add(this) }
    }
}

//将解析器构造方法前n个Type类型参数转换为Class类型，其它参数类型不变，其中n为解析器泛型数量
private fun ExecutableElement.getParameterSpecs(
    typeVariableNames: List<TypeVariableName>
): List<ParameterSpec> {
    val parameterList = ArrayList<ParameterSpec>()
    var typeIndex = 0
    val className = ClassName.get(Class::class.java)
    parameters.forEach { variableElement ->
        val variableType = variableElement.asType()  //参数类型
        if (variableType.toString() == "java.lang.reflect.Type[]") {
            typeVariableNames.forEach { typeVariableName ->
                //Type类型参数转Class<T>类型
                val classT = className.parameterizedBy(typeVariableName) //Class<T> 类型
                val variableName =
                    "${typeVariableName.name.lowercase(Locale.getDefault())}Type" //Class<T>类型参数名
                parameterList.add(ParameterSpec.builder(classT, variableName).build())
            }
        } else if (variableType.toString() == "java.lang.reflect.Type"
            && typeIndex < typeVariableNames.size
        ) {
            //Type类型参数转Class<T>类型
            val classT = className.parameterizedBy(typeVariableNames[typeIndex++])
            val variableName = variableElement.simpleName.toString()
            parameterList.add(ParameterSpec.builder(classT, variableName).build())
        } else {
            parameterList.add(ParameterSpec.get(variableElement))
        }
    }
    return parameterList
}

/**
 * @param variableElements 解析器构造方法参数列表
 * @param parameterSpecs 通过解析器构造方法参数列表转换而来的实际参数列表，parameterSpecs.size() >= variableElements.size()
 * @param typeCount 解析器泛型数量
 */
private fun getParamsName(
    variableElements: List<VariableElement>,
    parameterSpecs: List<ParameterSpec>,
    typeCount: Int,
    classToType: Boolean = false
): String {
    val sb = StringBuilder()
    var paramIndex = 0
    var variableIndex = 0
    val variableSize = variableElements.size
    val paramSize = parameterSpecs.size
    while (paramIndex < paramSize && variableIndex < variableSize) {
        if (variableIndex > 0) sb.append(", ")
        val type = variableElements[variableIndex++].asType()
        if ("java.lang.reflect.Type[]" == type.toString()) {
            sb.append("new Type[]{")
            for (i in 0 until typeCount) {
                if (i > 0) sb.append(", ")
                sb.append(parameterSpecs[paramIndex++].name)
            }
            sb.append("}")
        } else {
            val parameterSpec = parameterSpecs[paramIndex++]
            if (classToType && parameterSpec.type.isClassType()) {
                sb.append("(Type) ")
            }
            sb.append(parameterSpec.name)
        }
    }
    return sb.toString()
}

private fun TypeName.isClassType() = toString().startsWith("java.lang.Class")

//获取泛型字符串 比如:<T> 、<K,V>等等
private fun getTypeVariableString(typeVariableNames: List<TypeVariableName>): String {
    return if (typeVariableNames.isNotEmpty()) "<>" else ""
}

//获取onParser方法返回类型
private fun TypeElement.getOnParserFunReturnType(): TypeName? {
    val function = enclosedElements.find {
        it is ExecutableElement   //是方法
            && it.getModifiers().contains(Modifier.PUBLIC)  //public修饰
            && !it.getModifiers().contains(Modifier.STATIC) //非静态
            && it.simpleName.toString() == "onParse"  //onParse方法
            && it.parameters.size == 1  //只有一个参数
            && TypeName.get(it.parameters[0].asType())
            .toString() == "okhttp3.Response"  //参数是okhttp3.Response类型
    } ?: return null
    return TypeName.get((function as ExecutableElement).returnType)
}

@Throws(NoSuchElementException::class)
private fun TypeElement.checkParserValidClass(types: Types) {
    val elementQualifiedName = qualifiedName.toString()
    if (!modifiers.contains(Modifier.PUBLIC)) {
        throw NoSuchElementException("The class '$elementQualifiedName' must be public")
    }
    if (modifiers.contains(Modifier.ABSTRACT)) {
        val msg =
            "The class '$elementQualifiedName' is abstract. You can't annotate abstract classes with @${Parser::class.java.simpleName}"
        throw NoSuchElementException(msg)
    }

    val typeParameterList = typeParameters
    if (typeParameterList.size > 0) {
        //查找带 java.lang.reflect.Type 参数的构造方法
        val constructorFun = getPublicConstructors().filter { it.parameters.isNotEmpty() }
        val typeArgumentConstructorFun = constructorFun
            .findTypeArgumentConstructorFun(typeParameterList.size)
        if (typeArgumentConstructorFun == null) {
            val method = StringBuffer("public ${simpleName}(")
            for (i in typeParameterList.indices) {
                method.append("java.lang.reflect.Type")
                method.append(if (i == typeParameterList.lastIndex) ")" else ",")
            }
            val msg =
                "This class '$elementQualifiedName' must declare '$method' constructor method"
            throw NoSuchElementException(msg)
        }
    }

    val className = "rxhttp.wrapper.parse.Parser"
    if (!instanceOf(className, types)) {
        val msg =
            "The class '$elementQualifiedName' annotated with @${Parser::class.java.simpleName} must inherit from $className"
        throw NoSuchElementException(msg)
    }
}