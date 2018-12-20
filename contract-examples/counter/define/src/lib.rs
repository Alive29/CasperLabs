#![no_std]
#![feature(alloc)]

#[macro_use]
extern crate alloc;
use alloc::string::String;

extern crate common;
use common::ext::*;
use common::key::Key;
use common::value::Value;

fn inc(uref: &Key) {
    let one = Value::Int32(1);
    add(uref, &one);
}

fn get(uref: &Key) -> i32 {
    if let Value::Int32(i) = read(uref) {
        i
    } else {
        0
    }
}

#[no_mangle]
pub extern "C" fn counter_ext() {
    let i_key: Key = get_uref(0);
    let method_name: String = get_arg(0);
    match method_name.as_str() {
        "inc" => inc(&i_key),
        "get" => {
            let result = get(&i_key);
            ret(&result);
        }
        _ => panic!("Unknown method name!"),
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let export_name = String::from("counter_ext");
    let counter_local_key = new_uref();
    write(&counter_local_key, &Value::Int32(0));
    let _hash = store_function(&export_name, vec![counter_local_key]);
}
