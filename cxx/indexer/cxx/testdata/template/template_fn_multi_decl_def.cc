// Checks that we index function templates with mutliple forward declarations.
//- @f defines Decl1
template <typename T> void f();
//- @f defines Decl2
template <typename T> void f();
//- @f defines Defn
//- @f completes/uniquely Decl1
//- @f completes/uniquely Decl2
template <typename T> void f() { }
