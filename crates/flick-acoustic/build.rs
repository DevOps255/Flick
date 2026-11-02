//! Compiles the vendored ggwave library and the small C wrapper next to this crate.

fn main() {
    let ggwave = "../../third_party/ggwave";
    cc::Build::new()
        .cpp(true)
        .std("c++11")
        .include(format!("{ggwave}/include"))
        .include(format!("{ggwave}/src"))
        .file(format!("{ggwave}/src/ggwave.cpp"))
        .file("native/wrapper.cpp")
        .warnings(false)
        .compile("flick_ggwave");
    println!("cargo:rerun-if-changed=native/wrapper.cpp");
    // Every source and header that feeds the build. A change to a header must recompile too.
    println!("cargo:rerun-if-changed={ggwave}/src");
    println!("cargo:rerun-if-changed={ggwave}/include");
}
