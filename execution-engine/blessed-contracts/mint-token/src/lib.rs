#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;

mod capabilities;
mod mint;

use alloc::collections::BTreeMap;
use cl_std::contract_api;

#[no_mangle]
pub extern "C" fn call() {
    let _hash = contract_api::store_function("???", BTreeMap::new());
}
