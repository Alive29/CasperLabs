#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api;
use contract_ffi::value::uint::U512;

const UNBOND_METHOD_NAME: &str = "unbond";

enum Error {
    MissingArgument = 100,
    InvalidArgument = 101,
}

// Unbonding contract.
//
// Accepts unbonding amount (of type `Option<u64>`) as first argument.
// Unbonding with `None` unbonds all stakes in the PoS contract.
// Otherwise (`Some<u64>`) unbonds with part of the bonded stakes.
#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer = unwrap_or_revert(contract_api::get_pos(), 77);

    let unbond_amount: Option<U512> = match contract_api::get_arg::<Option<u64>>(0) {
        Some(Ok(Some(data))) => Some(U512::from(data)),
        Some(Ok(None)) => None,
        Some(Err(_)) => contract_api::revert(Error::InvalidArgument as u32),
        None => contract_api::revert(Error::MissingArgument as u32),
    };

    contract_api::call_contract(pos_pointer, &(UNBOND_METHOD_NAME, unbond_amount), &vec![])
}

fn unwrap_or_revert<T>(option: Option<T>, code: u32) -> T {
    if let Some(value) = option {
        value
    } else {
        contract_api::revert(code)
    }
}
