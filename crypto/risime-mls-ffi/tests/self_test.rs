#[test]
fn self_test_passes_on_host() {
    let out = uniffi_risime::self_test().expect("self test");
    assert!(out.starts_with("ok: epoch "), "{out}");
}

#[test]
fn errors_cross_the_ffi_mapping() {
    let c = uniffi_risime::MlsClient::new(b"x".to_vec()).unwrap();
    assert!(matches!(
        c.encrypt(b"nope".to_vec(), b"m".to_vec()),
        Err(uniffi_risime::RisiMlsError::UnknownGroup)
    ));
}
