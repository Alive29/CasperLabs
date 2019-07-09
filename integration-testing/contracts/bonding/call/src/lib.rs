#![no_std]
#![feature(alloc)]

#[macro_use]
extern crate alloc;
//extern crate cl_std;

extern crate common;
use common::contract_api;
use common::contract_api::pointers::UPointer;
use common::key::Key;
use common::value::uint::U512;
use common::contract_api::{call_contract, PurseTransferResult, get_uref, transfer_from_purse_to_purse, revert};

#[no_mangle]
pub extern "C" fn call() {
    let pos_public: UPointer<Key> = get_uref("pos").to_u_ptr().unwrap();
    let pos_contract: Key = contract_api::read(pos_public);
    let pos_pointer = pos_contract.to_c_ptr().unwrap();

    let source_purse = contract_api::main_purse();
    let bonding_purse = contract_api::create_purse();
    // Note: could be replaced with contract_api::get_arg(0) for a reusable bonding session contract
    // (as opposed to having a hard-coded amount).
    let bond_amount = U512::from(1);

    match transfer_from_purse_to_purse(source_purse, bonding_purse, bond_amount) {
        PurseTransferResult::TransferSuccessful => {
            let _result: () = call_contract(
                pos_pointer,
                &("bond", bond_amount, bonding_purse),
                &vec![Key::URef(bonding_purse.value())],
            );
        }

        PurseTransferResult::TransferError => revert(1324),
    }
}
