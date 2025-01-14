package tastyquery

import scala.concurrent.ExecutionContext.Implicits.global

import tastyquery.Contexts.*
import tastyquery.Exceptions.*
import tastyquery.Names.*
import tastyquery.Signatures.*
import tastyquery.Symbols.*
import tastyquery.Trees.*
import tastyquery.Types.*

import TestUtils.*

class SymbolSuite extends RestrictedUnpicklingSuite {

  /** Needed for correct resolving of ctor signatures */
  val fundamentalClasses: Seq[String] =
    Seq("java.lang.Object", "scala.Unit", "scala.AnyVal", "scala.annotation.targetName")

  def testWithContext(name: String, rootSymbolPath: String, extraRootSymbolPaths: String*)(using munit.Location)(
    body: Context ?=> Unit
  ): Unit =
    testWithContext(new munit.TestOptions(name), rootSymbolPath, extraRootSymbolPaths*)(body)

  def testWithContext(options: munit.TestOptions, rootSymbolPath: String, extraRootSymbolPaths: String*)(
    using munit.Location
  )(body: Context ?=> Unit): Unit =
    test(options) {
      for ctx <- getUnpicklingContext(rootSymbolPath, extraRootSymbolPaths*) yield body(using ctx)
    }

  def assertContainsExactly(
    owner: DeclaringSymbol,
    expectedDeclNames: Set[Name]
  )(using Context, munit.Location): Unit = {
    val decls = owner.declarations
    val actualDeclNames = decls.map(_.name).toSet

    val unexpectedDeclNames = actualDeclNames -- expectedDeclNames
    assert(
      unexpectedDeclNames.isEmpty,
      unexpectedDeclNames.map(_.toDebugString).mkString("Unexpected declarations:\n", "\n", "")
    )

    val missingDeclNames = expectedDeclNames -- actualDeclNames
    assert(
      missingDeclNames.isEmpty,
      missingDeclNames.map(_.toDebugString).mkString("Missing declarations:\n", "\n", "")
    )
  }

  testWithContext("top-level-package-object[class]-empty-package", "toplevelEmptyPackage$package$") {
    val toplevelEmptyPackage_packageClass = ctx.findTopLevelModuleClass("toplevelEmptyPackage$package")

    val tree = toplevelEmptyPackage_packageClass.tree.get

    assert(tree.name == termName("toplevelEmptyPackage$package").withObjectSuffix.toTypeName)
    assert(tree.symbol == toplevelEmptyPackage_packageClass)
  }

  testWithContext("top-level-package-object[value]-empty-package", "toplevelEmptyPackage$package$") {
    val toplevelEmptyPackage_packageValue = ctx.findStaticTerm("toplevelEmptyPackage$package")

    val (tree @ _: ValDef) = toplevelEmptyPackage_packageValue.tree.get: @unchecked

    assert(tree.name == termName("toplevelEmptyPackage$package"))
    assert(tree.symbol == toplevelEmptyPackage_packageValue)
  }

  testWithContext("top-level-package-object[companion class]-empty-package", "toplevelEmptyPackage$package$") {
    try
      ctx.findStaticType("toplevelEmptyPackage$package")
      fail(s"Expected not to resolve class toplevelEmptyPackage$$package")
    catch
      case ex: MemberNotFoundException =>
        assert(ex.name == typeName("toplevelEmptyPackage$package"))
        assert(ex.prefix == defn.EmptyPackage)
  }

  testWithContext("getPackageDecl", "simple_trees.ScalaObject$") {
    val pkg = ctx.findPackage("simple_trees")

    // Non-existent symbol
    assert(pkg.getPackageDecl(termName("nonexistentsubpackage")) == None)

    // Symbol exists but is not a package (whitebox knowledge: it is not loaded yet)
    assert(pkg.getPackageDecl(termName("ScalaObject")) == None)

    // ScalaObject actually exists as a term (but not as a package)
    assert(pkg.getDecl(name"ScalaObject").isDefined)

    // After loading ScalaObject, getPackageDecl still returns None for it
    assert(pkg.getPackageDecl(termName("ScalaObject")) == None)
  }

  testWithContext("basic-symbol-structure", "empty_class.EmptyClass") {
    ctx.findTopLevelClass("empty_class.EmptyClass")
    // EmptyClass is the only declaration in the empty_class package
    assertContainsExactly(ctx.findPackage("empty_class"), Set(tname"EmptyClass"))
  }

  testWithContext("basic-symbol-structure-nested", "simple_trees.nested.InNestedPackage") {
    ctx.findTopLevelClass("simple_trees.nested.InNestedPackage")
    // InNestedPackage is the only declaration in the simple_trees.nested package
    assertContainsExactly(ctx.findPackage("simple_trees.nested"), Set(tname"InNestedPackage"))
  }

  testWithContext("inner-class", "simple_trees.InnerClass") {
    val InnerClass = ctx.findTopLevelClass("simple_trees.InnerClass")
    // Inner is a declaration in InnerClass
    assert(InnerClass.getDecl(typeName("Inner")).isDefined)
  }

  testWithContext("empty-package-contains-no-packages", "simple_trees.SharedPackageReference$$package") {
    // simple_trees is not a subpackage of empty package
    assert(!defn.EmptyPackage.declarations.exists(_.isPackage))
  }

  testWithContext("class-parameter-is-a-decl", "simple_trees.ConstructorWithParameters") {
    assertContainsExactly(
      ctx.findTopLevelClass("simple_trees.ConstructorWithParameters"),
      Set(
        name"<init>",
        name"local",
        name"theVal",
        name"privateVal",
        // var and the setter for it
        name"theVar",
        name"theVar_="
      )
    )
  }

  testWithContext("class-type-parameter-is-not-a-decl", "simple_trees.GenericClass") {
    assertContainsExactly(
      ctx.findTopLevelClass("simple_trees.GenericClass"),
      Set(name"<init>", name"value", name"field", name"method", name"getter")
    )
  }

  testWithContext("nested-package-lookup", "simple_trees.nested.InNestedPackage") {
    import tastyquery.Types.*

    val InNestedPackageClass = ctx.findTopLevelClass("simple_trees.nested.InNestedPackage")

    assert(InNestedPackageClass.fullName.toString == "simple_trees.nested.InNestedPackage")

    val simpleTreesPkg = ctx.findPackage("simple_trees")

    assert(simpleTreesPkg.fullName.toString == "simple_trees")

    val (simpleTreesNestedPkg @ _: PackageSymbol) = simpleTreesPkg.getDecl(name"nested").get: @unchecked

    assert(simpleTreesNestedPkg.fullName.toString == "simple_trees.nested")

    assert(simpleTreesPkg.packageRef.member(name"nested") == simpleTreesNestedPkg)
  }

  testWithContext("basic-inheritance-same-root", "inheritance.SameTasty$", fundamentalClasses*) {
    val ParentClass = ctx.findStaticClass("inheritance.SameTasty.Parent")
    val ChildClass = ctx.findStaticClass("inheritance.SameTasty.Child")
    val SubClass = ctx.findStaticClass("inheritance.SameTasty.Sub")

    val fooMethod = SubClass.typeRef.member(name"foo")
    assert(clue(fooMethod.owner) == ChildClass)

    val getFooName = SignedName(termName("getFoo"), Signature(Nil, defn.ObjectClass.fullName))
    val getFooMethod = SubClass.typeRef.member(getFooName)
    assert(clue(getFooMethod.owner) == ParentClass)

    val FooTypeSym = SubClass.typeRef.member(tname"FooType")
    assert(FooTypeSym.isInstanceOf[TypeMemberSymbol])
    assert(clue(FooTypeSym.owner) == ChildClass)
  }

  testWithContext("complex-inheritance-same-root", "inheritance.SameTasty$", fundamentalClasses*) {
    //    Any     Mixin { type BarType; def bar: BarType; def getBar(): BarType = bar }
    //     │               │
    //  AnyRef         SubMixin { type BarType = Int; def bar: BarType = 29 }
    //     │               │
    //     └───────┬───────┘
    //             │
    //          WithMixin
    //             │
    //         SubWithMixin

    val SubWithMixinClass = ctx.findStaticClass("inheritance.SameTasty.SubWithMixin")
    val MixinClass = ctx.findStaticClass("inheritance.SameTasty.Mixin")
    val SubMixinClass = ctx.findStaticClass("inheritance.SameTasty.SubMixin")

    val barMethod = SubWithMixinClass.typeRef.member(name"bar")
    assert(clue(barMethod.owner) == SubMixinClass)

    val getBarName = SignedName(termName("getBar"), Signature(Nil, defn.ObjectClass.fullName))
    val getBarMethod = SubWithMixinClass.typeRef.member(getBarName)
    assert(clue(getBarMethod.owner) == MixinClass)

    val BarTypeSym = SubWithMixinClass.typeRef.member(tname"BarType")
    assert(BarTypeSym.isInstanceOf[TypeMemberSymbol])
    assert(clue(BarTypeSym.owner) == SubMixinClass)
  }

  testWithContext(
    "basic-inheritance-different-root",
    "inheritance.crosstasty.Parent",
    (Seq("inheritance.crosstasty.Child", "inheritance.crosstasty.Sub") ++ fundamentalClasses)*
  ) {
    val ParentClass = ctx.findStaticClass("inheritance.crosstasty.Parent")
    val ChildClass = ctx.findStaticClass("inheritance.crosstasty.Child")
    val SubClass = ctx.findStaticClass("inheritance.crosstasty.Sub")

    val fooMethod = SubClass.typeRef.member(name"foo")
    assert(clue(fooMethod.owner) == ChildClass)

    val getFooName = SignedName(termName("getFoo"), Signature(Nil, defn.ObjectClass.fullName))
    val getFooMethod = SubClass.typeRef.member(getFooName)
    assert(clue(getFooMethod.owner) == ParentClass)

    val FooTypeSym = SubClass.typeRef.member(tname"FooType")
    assert(FooTypeSym.isInstanceOf[TypeMemberSymbol])
    assert(clue(FooTypeSym.owner) == ChildClass)
  }

  testWithContext("MapView.withFilter", "scala.collection.MapView") {
    val MapView = ctx.findTopLevelClass("scala.collection.MapView")
    assert(MapView.getDecl(tpnme.RefinedClassMagic).isEmpty)
  }

  testWithContext("consistent-exception-in-parents-issue-168", "inheritance.crosstasty.Child") {
    val ChildClass = ctx.findStaticClass("inheritance.crosstasty.Child")
    intercept[MemberNotFoundException](ChildClass.parents)
    intercept[MemberNotFoundException](ChildClass.parents) // it's the same exception the second time
  }
}
