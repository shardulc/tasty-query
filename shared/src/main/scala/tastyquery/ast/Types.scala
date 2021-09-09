package tastyquery.ast

import dotty.tools.tasty.TastyFormat.NameTags
import tastyquery.Contexts.BaseContext
import tastyquery.ast.Constants.Constant
import tastyquery.ast.Names.{Name, QualifiedName, SimpleName, TermName, TypeName}
import tastyquery.ast.Symbols.*
import tastyquery.ast.Trees.{EmptyTree, Tree, TypeParam}

object Types {
  type Designator = Symbol | Name

  abstract class Type

  // ----- Type categories ----------------------------------------------

  // Every type is expected to inherit either TypeProxy or GroundType.

  /** Type proxies.
    * Each implementation is expected to redefine the `underlying` method.
    */
  abstract class TypeProxy extends Type {

    /** The type to which this proxy forwards operations. */
    def underlying(using BaseContext): Type
  }

  /** Non-proxy types */
  abstract class GroundType extends Type {}

  // ----- Marker traits ------------------------------------------------

  /** A marker trait for types that apply only to term symbols or that
    * represent higher-kinded types.
    */
  trait TermType extends Type

  trait MethodicType extends TermType

  /** A marker trait for types that can be types of values or prototypes of value types */
  trait ValueTypeOrProto extends TermType

  /** A marker trait for types that can be types of values or that are higher-kinded */
  trait ValueType extends ValueTypeOrProto

  /** A marker trait for types that are guaranteed to contain only a
    * single non-null value (they might contain null in addition).
    */
  trait SingletonType extends TypeProxy with ValueType {
    def isOverloaded: Boolean = false
  }

  trait Symbolic {
    def resolveToSymbol(using BaseContext): Symbol

    private[Types] def isStandard: Boolean
  }

  // ----- Type Proxies -------------------------------------------------

  abstract class NamedType extends TypeProxy with ValueType with Symbolic {
    self =>

    type ThisType >: this.type <: NamedType
    type ThisName <: Name

    val prefix: Type

    def designator: Designator

    protected def designator_=(d: Designator): Unit

    private var myName: Name = null

    def isType: Boolean = isInstanceOf[TypeRef]

    def isTerm: Boolean = isInstanceOf[TermRef]

    /** If designator is a name, this name. Otherwise, the original name
      * of the designator symbol.
      */
    final def name: ThisName = {
      if (myName == null) myName = computeName
      myName.asInstanceOf[ThisName]
    }

    private def computeName: Name = designator match {
      case name: Name  => name
      case sym: Symbol => sym.name
    }

    /** This is a temprorary hack to create symbols for standard types and objects, because Java classes and
      * Scala 2 standard library don't have .tasty files.
      */
    // overridden in package references
    private[Types] def createStandardSymbol(name: Name)(using ctx: BaseContext): Symbol = {
      val prefixSym = prefix.asInstanceOf[Symbolic] match {
        case t: TermRef =>
          val refType = t.underlying
          if (!refType.isInstanceOf[Symbolic])
            throw new ReferenceResolutionError(
              t,
              s"only references to terms, whose type is a combination of typeref, termref and thistype, are supported. Got type $refType"
            )
          refType.asInstanceOf[Symbolic].resolveToSymbol
        case other => other.resolveToSymbol
      }
      val newSymbol = name match {
        case tn: TypeName   => ClassSymbolFactory.createSymbol(name, prefixSym)
        case sn: SimpleName => RegularSymbolFactory.createSymbol(name, prefixSym)
        case _              => throw new SymbolLookupException(name, "unexpected name format")
      }
      prefixSym.asInstanceOf[DeclaringSymbol].addDecl(newSymbol)

      newSymbol
    }

    override def isStandard: Boolean = prefix.asInstanceOf[Symbolic].isStandard

    // overridden in package references
    override def resolveToSymbol(using BaseContext): Symbol = {
      if (!designator.isInstanceOf[Symbol]) {
        val name = designator.asInstanceOf[Name]
        if (prefix == NoPrefix) {
          throw new SymbolLookupException(name, "reference by name to a local symbol")
        }
        val prefixSym = prefix.asInstanceOf[Symbolic] match {
          case t: TermRef =>
            val underlyingType = t.underlying
            if (underlyingType == NoType)
              throw new ReferenceResolutionError(t, s"the term does not have a type")
            if (!underlyingType.isInstanceOf[Symbolic])
              throw new ReferenceResolutionError(
                t,
                s"only references to terms, whose type is a combination of typeref, termref and thistype, are supported. Got type $underlyingType"
              )
            underlyingType.asInstanceOf[Symbolic].resolveToSymbol
          case other => other.resolveToSymbol
        }
        designator = {
          val symOption = prefixSym.asInstanceOf[DeclaringSymbol].getDecl(name)
          symOption.getOrElse(if (isStandard) createStandardSymbol(name) else throw new SymbolLookupException(name))
        }
      }
      designator.asInstanceOf[Symbol]
    }
  }

  /** A reference to an implicit definition. This can be either a TermRef or a
    *  Implicits.RenamedImplicitRef.
    */
  trait ImplicitRef {
    def implicitName: TermName
    def underlyingRef: TermRef
  }

  class ReferenceResolutionError(val ref: TermRef, explanation: String = "")
      extends RuntimeException(
        ReferenceResolutionError.addExplanation(s"Could not compute type of the term, referenced by $ref", explanation)
      )

  object ReferenceResolutionError {
    def unapply(e: ReferenceResolutionError): Option[TermRef] = Some(e.ref)

    def addExplanation(msg: String, explanation: String): String =
      if (explanation.isEmpty) msg else s"$msg: $explanation"
  }

  /** The singleton type for path prefix#myDesignator. */
  case class TermRef(override val prefix: Type, var myDesignator: Designator)
      extends NamedType
      with SingletonType
      with ImplicitRef {

    type ThisType = TermRef
    type ThisName = TermName

    override def designator: Designator = myDesignator

    override protected def designator_=(d: Designator): Unit = myDesignator = d

    override def underlying(using ctx: BaseContext): Type = {
      val termSymbol = resolveToSymbol
      try {
        termSymbol.tree.tpe
      } catch {
        case e => throw new ReferenceResolutionError(this, e.getMessage)
      }
    }

    override def isOverloaded: Boolean = ???

    def implicitName: TermName = name

    def underlyingRef: TermRef = this
  }

  class PackageRef(val packageName: TermName) extends NamedType with SingletonType {
    var packageSymbol: PackageClassSymbol = null

    override def designator: Designator =
      if (packageSymbol == null) packageName else packageSymbol

    override protected def designator_=(d: Designator): Unit = throw UnsupportedOperationException(
      s"Can't assign designator of a package"
    )

    override def underlying(using BaseContext): Type = ???

    // TODO: root package?
    override val prefix: Type = NoType

    override def isStandard: Boolean = {
      def isStandard(name: TermName): Boolean =
        name match {
          case QualifiedName(NameTags.QUALIFIED, prefix, _) => isStandard(prefix)
          case SimpleName(name)                             => name == "java" || name == "scala"
          case _ => throw IllegalArgumentException(s"Unexpected package name: $packageName")
        }
      isStandard(packageName)
    }

    override def createStandardSymbol(name: Name)(using ctx: BaseContext): Symbol =
      ctx.createPackageSymbolIfNew(name.asInstanceOf[TermName], ctx.defn.RootPackage)

    override def resolveToSymbol(using ctx: BaseContext): PackageClassSymbol = {
      if (packageSymbol == null) {
        val symOption = ctx.defn.RootPackage.findPackageSymbol(packageName)
        packageSymbol = symOption.getOrElse(
          if (isStandard) ctx.createPackageSymbolIfNew(packageName, ctx.defn.RootPackage)
          else throw new SymbolLookupException(packageName)
        )
      }
      packageSymbol
    }

    override def toString: String = s"PackageRef($packageName)"
  }

  object PackageRef {
    def unapply(r: PackageRef): Option[Name] = Some(r.packageName)
  }

  case class TypeRef(override val prefix: Type, private var myDesignator: Designator) extends NamedType {

    type ThisType = TypeRef
    type ThisName = TypeName

    override def designator: Designator = myDesignator

    override protected def designator_=(d: Designator): Unit = myDesignator = d

    override def underlying(using BaseContext): Type = ???
  }

  case object NoPrefix extends Type

  class PackageTypeRef(packageName: TermName) extends TypeRef(NoPrefix, packageName) {
    private val packageRef = PackageRef(packageName)

    override def isStandard: Boolean = packageRef.isStandard

    override private[Types] def createStandardSymbol(name: Name)(using BaseContext) =
      packageRef.createStandardSymbol(name)

    override def resolveToSymbol(using BaseContext): Symbol = packageRef.resolveToSymbol
  }

  case class ThisType(tref: TypeRef) extends TypeProxy with SingletonType with Symbolic {
    override def underlying(using BaseContext): Type = ???

    override def resolveToSymbol(using BaseContext): Symbol = tref.resolveToSymbol

    override private[Types] def isStandard = tref.isStandard
  }

  /** A constant type with single `value`. */
  case class ConstantType(value: Constant) extends TypeProxy with SingletonType {
    override def underlying(using BaseContext): Type = ???
  }

  /** A type application `C[T_1, ..., T_n]`
    * Typebounds for wildcard application: C[_], C[?]
    */
  case class AppliedType(tycon: Type, args: List[Type | TypeBounds]) extends TypeProxy with ValueType {
    override def underlying(using BaseContext): Type = tycon
  }

  /** A by-name parameter type of the form `=> T`, or the type of a method with no parameter list. */
  case class ExprType(resType: Type) extends TypeProxy with MethodicType {
    override def underlying(using BaseContext): Type = resType
  }

  case class TypeLambda(params: List[TypeParam], resultTypeCtor: TypeLambda => Type) extends TypeProxy with TermType {
    val resultType = resultTypeCtor(this)

    override def underlying(using BaseContext): Type = ???

    override def toString: String = s"TypeLambda($params, $resultType)"
  }

  case class TypeParamRef(binder: TypeLambda, num: Int) extends TypeProxy with ValueType {
    override def underlying(using BaseContext): Type = ???

    override def toString: String = binder.params(num).name.toString
  }

  /** typ @ annot */
  case class AnnotatedType(typ: Type, annotation: Tree) extends TypeProxy with ValueType {
    override def underlying(using BaseContext): Type = typ
  }

  /** A refined type parent { refinement }
    *  @param parent      The type being refined
    *  @param refinedName The name of the refinement declaration
    *  @param refinedInfo The info of the refinement declaration
    */
  case class RefinedType(parent: Type, refinedName: Name, refinedInfo: TypeBounds) extends TypeProxy with ValueType {
    override def underlying(using BaseContext): Type = parent
  }

  trait TypeBounds(low: Type, high: Type)

  case class RealTypeBounds(low: Type, high: Type) extends TypeBounds(low, high)

  case class TypeAlias(alias: Type) extends TypeProxy with TypeBounds(alias, alias) {
    override def underlying(using BaseContext): Type = alias
  }

  // ----- Ground Types -------------------------------------------------

  case class OrType(first: Type, second: Type) extends GroundType with ValueType

  case class AndType(first: Type, second: Type) extends GroundType with ValueType

  case object NoType extends GroundType
}
