//! `adb push` + `adb shell` this to check the native MLS core on a real Android device or
//! emulator, without any app wiring. Exit code 0 = pass.
fn main() {
    match uniffi_risime::self_test() {
        Ok(summary) => println!("risime-mls self-test {summary}"),
        Err(e) => {
            eprintln!("risime-mls self-test FAILED: {e}");
            std::process::exit(1);
        }
    }
}
