#![no_std]
#![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::{self, TransferResult};
use contract_ffi::value::U512;

const TRANSFER_AMOUNT: u32 = 50_000_000 + 1000;

enum Error {
    MissingArgument = 100,
    InvalidArgument = 101,
}

#[no_mangle]
pub extern "C" fn call() {
    let public_key = match contract_api::get_arg(0) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(Error::InvalidArgument as u32),
        None => contract_api::revert(Error::MissingArgument as u32),
    };
    let amount = U512::from(TRANSFER_AMOUNT);

    let result = contract_api::transfer_to_account(public_key, amount);

    assert_ne!(result, TransferResult::TransferError);
}
