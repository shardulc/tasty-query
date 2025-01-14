package tastyquery

import tastyquery.Contexts.*
import tastyquery.Flags.*
import tastyquery.Names.*
import tastyquery.Symbols.*
import tastyquery.Types.*

final class Definitions private[tastyquery] (ctx: Context, rootPackage: PackageSymbol, emptyPackage: PackageSymbol):
  private given Context = ctx

  // Core packages

  val RootPackage = rootPackage
  val EmptyPackage = emptyPackage

  val scalaPackage = RootPackage.getPackageDeclOrCreate(nme.scalaPackageName)
  private val javaPackage = RootPackage.getPackageDeclOrCreate(nme.javaPackageName)
  val javaLangPackage = javaPackage.getPackageDeclOrCreate(nme.langPackageName)

  private val scalaAnnotationPackage =
    scalaPackage.getPackageDeclOrCreate(termName("annotation"))
  private val scalaCollectionPackage =
    scalaPackage.getPackageDeclOrCreate(termName("collection"))
  private[tastyquery] val scalaCompiletimePackage =
    scalaPackage.getPackageDeclOrCreate(termName("compiletime"))
  private val scalaCollectionImmutablePackage =
    scalaCollectionPackage.getPackageDeclOrCreate(termName("immutable"))

  // Cached TypeRef's for core types

  val AnyType: TypeRef = TypeRef(scalaPackage.packageRef, typeName("Any"))
  val MatchableType: TypeRef = TypeRef(scalaPackage.packageRef, typeName("Matchable"))
  val AnyRefType: TypeRef = TypeRef(scalaPackage.packageRef, typeName("AnyRef"))
  val AnyValType: TypeRef = TypeRef(scalaPackage.packageRef, typeName("AnyVal"))

  val NullType: TypeRef = TypeRef(scalaPackage.packageRef, typeName("Null"))
  val NothingType: TypeRef = TypeRef(scalaPackage.packageRef, typeName("Nothing"))

  val ObjectType: TypeRef = TypeRef(javaLangPackage.packageRef, typeName("Object"))

  val ArrayTypeUnapplied: TypeRef = TypeRef(scalaPackage.packageRef, typeName("Array"))
  def ArrayTypeOf(tpe: Type): AppliedType = AppliedType(ArrayTypeUnapplied, List(tpe))

  val SeqTypeUnapplied: TypeRef = TypeRef(scalaCollectionImmutablePackage.packageRef, typeName("Seq"))
  def SeqTypeOf(tpe: Type): AppliedType = AppliedType(SeqTypeUnapplied, List(tpe))

  val IntType: TypeRef = TypeRef(scalaPackage.packageRef, typeName("Int"))
  val LongType: TypeRef = TypeRef(scalaPackage.packageRef, typeName("Long"))
  val FloatType: TypeRef = TypeRef(scalaPackage.packageRef, typeName("Float"))
  val DoubleType: TypeRef = TypeRef(scalaPackage.packageRef, typeName("Double"))
  val BooleanType: TypeRef = TypeRef(scalaPackage.packageRef, typeName("Boolean"))
  val ByteType: TypeRef = TypeRef(scalaPackage.packageRef, typeName("Byte"))
  val ShortType: TypeRef = TypeRef(scalaPackage.packageRef, typeName("Short"))
  val CharType: TypeRef = TypeRef(scalaPackage.packageRef, typeName("Char"))
  val UnitType: TypeRef = TypeRef(scalaPackage.packageRef, typeName("Unit"))

  val StringType: TypeRef = TypeRef(javaLangPackage.packageRef, typeName("String"))

  val UnappliedClassType: TypeRef = TypeRef(javaLangPackage.packageRef, typeName("Class"))
  def ClassTypeOf(tpe: Type): AppliedType = AppliedType(UnappliedClassType, List(tpe))

  val ThrowableType: TypeRef = TypeRef(javaLangPackage.packageRef, typeName("Throwable"))

  // Magic symbols that are not found on the classpath, but rather created by hand

  private def createSpecialClass(name: TypeName, parents: List[Type], flags: FlagSet): ClassSymbol =
    val cls = ClassSymbol.create(name, scalaPackage)
    cls.withTypeParams(Nil)
    cls.withParentsDirect(parents)
    cls.withFlags(flags, None)
    cls.setAnnotations(Nil)
    cls.checkCompleted()
    cls

  private def createSpecialMethod(
    owner: ClassSymbol,
    name: TermName,
    tpe: MethodicType,
    flags: FlagSet = EmptyFlagSet
  ): TermSymbol =
    val sym = TermSymbol
      .create(name, owner)
      .withFlags(Method | flags, privateWithin = None)
      .withDeclaredType(tpe)
      .setAnnotations(Nil)
    sym.checkCompleted()
    sym
  end createSpecialMethod

  val AnyClass = createSpecialClass(typeName("Any"), Nil, Abstract)
    .withSpecialErasure(() => ErasedTypeRef.ClassRef(ObjectClass))

  val MatchableClass = createSpecialClass(typeName("Matchable"), AnyClass.typeRef :: Nil, Trait)
    .withSpecialErasure(() => ErasedTypeRef.ClassRef(ObjectClass))

  val NullClass =
    createSpecialClass(typeName("Null"), AnyClass.typeRef :: MatchableClass.typeRef :: Nil, Abstract | Final)

  val NothingClass = createSpecialClass(typeName("Nothing"), AnyClass.typeRef :: Nil, Abstract | Final)

  val NothingAnyBounds = RealTypeBounds(NothingClass.typeRef, AnyClass.typeRef)

  locally {
    val andOrParamNames = List(typeName("A"), typeName("B"))

    val andTypeAlias = TypeMemberSymbol.create(typeName("&"), scalaPackage)
    andTypeAlias.withFlags(EmptyFlagSet, None)
    andTypeAlias.withDefinition(
      TypeMemberDefinition.TypeAlias(
        PolyType(andOrParamNames)(
          pt => List(NothingAnyBounds, NothingAnyBounds),
          pt => AndType(pt.paramRefs(0), pt.paramRefs(1))
        )
      )
    )

    val orTypeAlias = TypeMemberSymbol.create(typeName("|"), scalaPackage)
    orTypeAlias.withFlags(EmptyFlagSet, None)
    orTypeAlias.withDefinition(
      TypeMemberDefinition.TypeAlias(
        PolyType(andOrParamNames)(
          pt => List(NothingAnyBounds, NothingAnyBounds),
          pt => OrType(pt.paramRefs(0), pt.paramRefs(1))
        )
      )
    )

    val AnyRefAlias = TypeMemberSymbol.create(typeName("AnyRef"), scalaPackage)
    AnyRefAlias.withFlags(EmptyFlagSet, None)
    AnyRefAlias.withDefinition(TypeMemberDefinition.TypeAlias(ObjectType))

    // See `case NOtpe` in `PickleReader.scala`
    val scala2NoTypeAlias = TypeMemberSymbol.create(typeName("<notype>"), scalaPackage)
    scala2NoTypeAlias.withFlags(Synthetic, None)
    scala2NoTypeAlias.withDefinition(TypeMemberDefinition.TypeAlias(NothingType))
  }

  private def equalityMethodType: MethodType =
    MethodType(List(termName("that")), List(AnyType), BooleanType)

  private def instanceTestPolyType(resultType: PolyType => Type): PolyType =
    PolyType(List(typeName("A")))(_ => List(NothingAnyBounds), resultType)

  val Any_== = createSpecialMethod(AnyClass, nme.m_==, equalityMethodType, Final)
  val Any_!= = createSpecialMethod(AnyClass, nme.m_!=, equalityMethodType, Final)
  val Any_## = createSpecialMethod(AnyClass, nme.m_##, ExprType(IntType), Final)

  val Any_equals = createSpecialMethod(AnyClass, nme.m_equals, equalityMethodType)
  val Any_hashCode = createSpecialMethod(AnyClass, nme.m_hashCode, MethodType(Nil, Nil, IntType))

  val Any_toString = createSpecialMethod(AnyClass, nme.m_toString, MethodType(Nil, Nil, StringType))

  val Any_isInstanceOf =
    createSpecialMethod(AnyClass, nme.m_isInstanceOf, instanceTestPolyType(_ => BooleanType), Final)
  val Any_asInstanceOf =
    createSpecialMethod(AnyClass, nme.m_asInstanceOf, instanceTestPolyType(_.paramRefs.head), Final)

  val Any_typeTest =
    createSpecialMethod(
      AnyClass,
      termName("$isInstanceOf$"),
      instanceTestPolyType(_ => BooleanType),
      Final | Synthetic | Artifact
    )
  val Any_typeCast =
    createSpecialMethod(
      AnyClass,
      termName("$asInstanceOf$"),
      instanceTestPolyType(_.paramRefs.head),
      Final | Synthetic | Artifact
    )

  val Any_getClass =
    // def getClass[A >: this.type](): Class[? <: A]
    val tpe = PolyType(List(typeName("A")))(
      pt => List(RealTypeBounds(AnyClass.thisType, AnyType)),
      pt => MethodType(Nil, Nil, ClassTypeOf(WildcardTypeBounds(RealTypeBounds(NothingType, pt.paramRefs.head))))
    )
    createSpecialMethod(AnyClass, nme.m_getClass, tpe, Final)
  end Any_getClass

  private[tastyquery] def createObjectMagicMethods(cls: ClassSymbol): Unit =
    createSpecialMethod(cls, nme.m_eq, equalityMethodType, Final)
    createSpecialMethod(cls, nme.m_ne, equalityMethodType, Final)

    val synchronizedTpe = PolyType(List(typeName("A")))(
      pt => List(NothingAnyBounds),
      pt => MethodType(List(termName("x")), List(pt.paramRefs.head), pt.paramRefs.head)
    )
    createSpecialMethod(cls, nme.m_synchronized, synchronizedTpe)
  end createObjectMagicMethods

  lazy val Object_eq: TermSymbol = ObjectClass.findNonOverloadedDecl(nme.m_eq)
  lazy val Object_ne: TermSymbol = ObjectClass.findNonOverloadedDecl(nme.m_ne)
  lazy val Object_synchronized: TermSymbol = ObjectClass.findNonOverloadedDecl(nme.m_synchronized)

  private def createSpecialPolyClass(
    name: TypeName,
    paramFlags: FlagSet,
    parentConstrs: Type => List[Type]
  ): ClassSymbol =
    val cls = ClassSymbol.create(name, scalaPackage)

    val tparam = ClassTypeParamSymbol.create(typeName("T"), cls)
    tparam.withFlags(ClassTypeParam, None)
    tparam.setBounds(NothingAnyBounds)

    cls.withTypeParams(tparam :: Nil)
    cls.withFlags(EmptyFlagSet | Artifact, None)

    val parents = parentConstrs(TypeRef(NoPrefix, tparam))
    cls.withParentsDirect(parents)
    cls
  end createSpecialPolyClass

  val ByNameParamClass2x: ClassSymbol =
    createSpecialPolyClass(tpnme.ByNameParamClassMagic, Covariant, _ => List(AnyType))
      .withSpecialErasure(() => ErasedTypeRef.ClassRef(Function0Class))

  val RepeatedParamClass: ClassSymbol =
    createSpecialPolyClass(tpnme.RepeatedParamClassMagic, Covariant, tp => List(ObjectType, SeqTypeOf(tp)))
      .withSpecialErasure(() => ErasedTypeRef.ClassRef(SeqClass))

  /** Creates one of the `ContextFunctionNClass` classes.
    *
    * There are of the form:
    *
    * ```scala
    * trait ContextFunctionN[-T0,...,-T{N-1}, +R] extends Object {
    *   def apply(using $x0: T0, ..., $x{N_1}: T{N-1}): R
    * }
    * ```
    */
  private def createContextFunctionNClass(n: Int): ClassSymbol =
    val name = typeName("ContextFunction" + n)
    val cls = ClassSymbol.create(name, scalaPackage)

    cls.withFlags(Trait | NoInitsInterface, None)
    cls.withParentsDirect(ObjectType :: Nil)

    cls.withSpecialErasure { () =>
      ErasedTypeRef.ClassRef(scalaPackage.requiredClass("Function" + n))
    }

    val inputTypeParams = List.tabulate(n) { i =>
      ClassTypeParamSymbol
        .create(typeName("T" + i), cls)
        .withFlags(ClassTypeParam | Contravariant, None)
        .setBounds(NothingAnyBounds)
    }
    val resultTypeParam =
      ClassTypeParamSymbol
        .create(typeName("R"), cls)
        .withFlags(ClassTypeParam | Covariant, None)
        .setBounds(NothingAnyBounds)

    val allTypeParams = inputTypeParams :+ resultTypeParam
    cls.withTypeParams(allTypeParams)

    val applyMethod = TermSymbol.create(termName("apply"), cls)
    applyMethod.withFlags(Method | Abstract, None)
    applyMethod.withDeclaredType(
      MethodType(List.tabulate(n)(i => termName("x" + i)))(
        mt => inputTypeParams.map(_.typeRef),
        mt => resultTypeParam.typeRef
      )
    )

    cls
  end createContextFunctionNClass

  locally {
    for (n <- 0 to 22)
      createContextFunctionNClass(n)
  }

  // Derived symbols, found on the classpath

  extension (pkg: PackageSymbol)
    private def requiredClass(name: String): ClassSymbol = pkg.getDecl(typeName(name)).get.asClass
    private def optionalClass(name: String): Option[ClassSymbol] = pkg.getDecl(typeName(name)).map(_.asClass)

  lazy val ObjectClass = javaLangPackage.requiredClass("Object")

  lazy val AnyValClass = scalaPackage.requiredClass("AnyVal")
  lazy val ArrayClass = scalaPackage.requiredClass("Array")
  lazy val SeqClass = scalaCollectionImmutablePackage.requiredClass("Seq")
  lazy val Function0Class = scalaPackage.requiredClass("Function0")

  lazy val IntClass = scalaPackage.requiredClass("Int")
  lazy val LongClass = scalaPackage.requiredClass("Long")
  lazy val FloatClass = scalaPackage.requiredClass("Float")
  lazy val DoubleClass = scalaPackage.requiredClass("Double")
  lazy val BooleanClass = scalaPackage.requiredClass("Boolean")
  lazy val ByteClass = scalaPackage.requiredClass("Byte")
  lazy val ShortClass = scalaPackage.requiredClass("Short")
  lazy val CharClass = scalaPackage.requiredClass("Char")
  lazy val UnitClass = scalaPackage.requiredClass("Unit")

  lazy val StringClass = javaLangPackage.requiredClass("String")

  private[tastyquery] lazy val targetNameAnnotClass = scalaAnnotationPackage.optionalClass("targetName")

  def isPrimitiveValueClass(sym: ClassSymbol): Boolean =
    sym == IntClass || sym == LongClass || sym == FloatClass || sym == DoubleClass ||
      sym == BooleanClass || sym == ByteClass || sym == ShortClass || sym == CharClass ||
      sym == UnitClass

  lazy val uninitializedMethod: Option[TermSymbol] =
    scalaCompiletimePackage.getDecl(moduleClassName("package$package")).flatMap { packageObjectClass =>
      packageObjectClass.asClass.getDecl(termName("uninitialized"))
    }
  end uninitializedMethod

  private[tastyquery] lazy val uninitializedMethodTermRef: TermRef =
    TermRef(TermRef(PackageRef(defn.scalaCompiletimePackage), termName("package$package")), termName("uninitialized"))

end Definitions
