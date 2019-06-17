#![no_std]
#![feature(alloc)]

extern crate alloc;
use alloc::vec::Vec;

extern crate common;
use common::contract_api::call_contract;
use common::contract_api::pointers::ContractPointer;

#[no_mangle]
pub extern "C" fn call() {
    //This hash comes from blake2b256( [0;32] ++ [0;8] ++ [0;4] )
    //                                  pk    ++ nonce ++ function_counter 
    // nonce=0, function_counter=0
    let hash = ContractPointer::Hash([164, 102, 153, 51, 236, 214, 169, 167, 126, 44, 250, 247, 179, 214, 203, 229, 239, 69, 145, 25, 5, 153, 113, 55, 255, 188, 176, 201, 7, 4, 42, 100]
);
    let arg = "dummy";
    let _result: () = call_contract(hash.clone(), &arg, &Vec::new());
}
