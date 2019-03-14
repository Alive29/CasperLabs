pub mod account;
pub mod contract;

use crate::bytesrepr::{Error, FromBytes, ToBytes};
use crate::key::{Key, UREF_SIZE};
use alloc::string::String;
use alloc::vec::Vec;
use core::iter;

pub use self::account::Account;
pub use self::contract::Contract;

#[derive(PartialEq, Eq, Clone, Debug)]
pub enum Value {
    Int32(i32),
    ByteArray(Vec<u8>),
    ListInt32(Vec<i32>),
    String(String),
    ListString(Vec<String>),
    NamedKey(String, Key),
    Account(account::Account),
    Contract(contract::Contract),
}

const INT32_ID: u8 = 0;
const BYTEARRAY_ID: u8 = 1;
const LISTINT32_ID: u8 = 2;
const STRING_ID: u8 = 3;
const ACCT_ID: u8 = 4;
const CONTRACT_ID: u8 = 5;
const NAMEDKEY_ID: u8 = 6;
const LISTSTRING_ID: u8 = 7;

use self::Value::*;

impl ToBytes for Value {
    fn to_bytes(&self) -> Vec<u8> {
        match self {
            Int32(i) => {
                let mut result = Vec::with_capacity(5);
                result.push(INT32_ID);
                result.append(&mut i.to_bytes());
                result
            }
            ByteArray(arr) => {
                let mut result = Vec::with_capacity(5 + arr.len());
                result.push(BYTEARRAY_ID);
                result.append(&mut arr.to_bytes());
                result
            }
            ListInt32(arr) => {
                let mut result = Vec::with_capacity(5 + 4 * arr.len());
                result.push(LISTINT32_ID);
                result.append(&mut arr.to_bytes());
                result
            }
            String(s) => {
                let mut result = Vec::with_capacity(5 + s.len());
                result.push(STRING_ID);
                result.append(&mut s.to_bytes());
                result
            }
            Account(a) => {
                let mut result = Vec::new();
                result.push(ACCT_ID);
                result.append(&mut a.to_bytes());
                result
            }
            Contract(c) => iter::once(CONTRACT_ID).chain(c.to_bytes()).collect(),
            NamedKey(n, k) => {
                let size: usize = 1 + //size for ID
                  4 +                 //size for length of String
                  n.len() +           //size of String
                  UREF_SIZE; //size of urefs
                let mut result = Vec::with_capacity(size);
                result.push(NAMEDKEY_ID);
                result.append(&mut n.to_bytes());
                result.append(&mut k.to_bytes());
                result
            }
            ListString(arr) => {
                let mut result = Vec::with_capacity(5 + arr.len());
                result.push(LISTSTRING_ID);
                result.append(&mut arr.to_bytes());
                result
            }
        }
    }
}
impl FromBytes for Value {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (id, rest): (u8, &[u8]) = FromBytes::from_bytes(bytes)?;
        match id {
            INT32_ID => {
                let (i, rem): (i32, &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((Int32(i), rem))
            }
            BYTEARRAY_ID => {
                let (arr, rem): (Vec<u8>, &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((ByteArray(arr), rem))
            }
            LISTINT32_ID => {
                let (arr, rem): (Vec<i32>, &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((ListInt32(arr), rem))
            }
            STRING_ID => {
                let (s, rem): (String, &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((String(s), rem))
            }
            ACCT_ID => {
                let (a, rem): (account::Account, &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((Account(a), rem))
            }
            CONTRACT_ID => {
                let (c, rem): (contract::Contract, &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((Contract(c), rem))
            }
            NAMEDKEY_ID => {
                let (name, rem1): (String, &[u8]) = FromBytes::from_bytes(rest)?;
                let (key, rem2): (Key, &[u8]) = FromBytes::from_bytes(rem1)?;
                Ok((NamedKey(name, key), rem2))
            }
            LISTSTRING_ID => {
                let (arr, rem): (Vec<String>, &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((ListString(arr), rem))
            }
            _ => Err(Error::FormattingError),
        }
    }
}

impl Value {
    pub fn type_string(&self) -> String {
        match self {
            Int32(_) => String::from("Int32"),
            ListInt32(_) => String::from("List[Int32]"),
            String(_) => String::from("String"),
            ByteArray(_) => String::from("ByteArray"),
            Account(_) => String::from("Account"),
            Contract(_) => String::from("Contract"),
            NamedKey(_, _) => String::from("NamedKey"),
            ListString(_) => String::from("List[String]"),
        }
    }

    pub fn as_account(&self) -> &account::Account {
        match self {
            Account(a) => a,
            _ => panic!("Not an account: {:?}", self),
        }
    }
}

impl From<account::Account> for Value {
    fn from(a: account::Account) -> Self {
        Value::Account(a)
    }
}

impl From<contract::Contract> for Value {
    fn from(c: contract::Contract) -> Self {
        Value::Contract(c)
    }
}
