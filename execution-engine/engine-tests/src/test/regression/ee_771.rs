use engine_test_support::{
    internal::{ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_GENESIS_CONFIG},
    DEFAULT_ACCOUNT_ADDR,
};

const CONTRACT_EE_771_REGRESSION: &str = "ee_771_regression.wasm";

#[ignore]
#[test]
fn should_run_ee_771_regression() {
    let exec_request =
        ExecuteRequestBuilder::standard(DEFAULT_ACCOUNT_ADDR, CONTRACT_EE_771_REGRESSION, ())
            .build();

    let result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request)
        .commit()
        .finish();

    let response = result
        .builder()
        .get_exec_response(0)
        .expect("should have a response")
        .to_owned();

    // let error_message = utils::get_error_message(response);
    let error = response[0].error().expect("should have error");
    assert_eq!(
        format!("{}", error),
        "Function not found: functiondoesnotexist."
    );
}
