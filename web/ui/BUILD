package(default_visibility = ["//kythe:default_visibility"])

filegroup(
    name = "ui",
    srcs = [":dev-js"] + glob(["resources/public/**"]),
    tags = ["manual"],
)

genrule(
    name = "dev-js",
    srcs = glob([
        "project.clj",
        "src-cljs/**/*.cljs",
    ]),
    outs = [
        "resources/public/js/main.js",
        "resources/public/js/licenses.txt",
    ],
    cmd = "\n".join([
        "lein=\"$$PWD/$(location //third_party/leiningen:lein)\"",
        "export HOME=/tmp/lein",
        "mkdir -p $$HOME",
        "cd kythe/web/ui",
        "$$lein deps",
        "$$lein cljsbuild once dev",
        "$$lein licenses | sort > licenses.txt",
        "cd ../../..",
        "mv kythe/web/ui/resources/public/js/main.js $(location resources/public/js/main.js)",
        "mv kythe/web/ui/resources/public/js/main.js $(location resources/public/js/main.js)",
        "mv kythe/web/ui/licenses.txt $(location resources/public/js/licenses.txt)",
    ]),
    heuristic_label_expansion = False,
    tags = ["manual"],
    tools = ["//third_party/leiningen:lein"],
)
