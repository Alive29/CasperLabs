use super::alloc::vec::Vec;
use super::bytesrepr::{Error, FromBytes, ToBytes};
use crate::contract_api::pointers::*;
use core::cmp::Ordering;
use core::mem;

#[repr(C)]
#[derive(Clone, Copy, Debug, Hash)]
pub enum AccessRights {
    Eqv,
    Read,
    Write,
    Add,
    ReadAdd,
    ReadWrite,
}

use AccessRights::*;

/// Partial order of the access rights.
/// Since there are three distinct types of access rights to a resource
/// (Read, Write, Add; Eqv is implicit for each one), and various combinations
/// of them, some of them can be compared to each other.
///
/// This ordering is created so that it is possible to do:
///
/// ```
/// use casperlabs_contract_ffi::key::AccessRights;
///
/// // Imaginary definition of Key
/// pub struct Key {
///   pub access_right: AccessRights,
/// };
///
/// impl Key {
///   fn new(access_right: AccessRights) -> Key {
///     Key { access_right }
///   }
/// }
///
/// // Imaginary definition of resources to which we want restrict access to.
/// type Resource = u32;
///
/// // Imaginary error
/// type Error = String;
///
/// fn read(resource: Resource, key: Key) -> Result<Resource, Error> {
///   // note that the test is "greater or equal".
///   // This will pass for Read, ReadWrite, ReadAdd access rights.
///   if key.access_right >= AccessRights::Read {
///     Ok(resource)
///   } else {
///     Err("Invalid access rights to the resource.".to_owned())
///   }
/// }
///
/// assert!(read(10u32, Key::new(AccessRights::Read)).is_ok());
/// assert!(read(10u32, Key::new(AccessRights::ReadAdd)).is_ok());
/// assert!(read(10u32, Key::new(AccessRights::ReadWrite)).is_ok());
/// assert!(read(10u32, Key::new(AccessRights::Write)).is_err());
/// ```
///
///
/// and the tests passes for: Read, ReadAdd and ReadWrite.
impl PartialOrd for AccessRights {
    //  !!! Caution !!! Do not reorder
    fn partial_cmp(&self, other: &AccessRights) -> Option<Ordering> {
        match (self, other) {
            (Eqv, Eqv) => Some(Ordering::Equal),
            (Eqv, _) => Some(Ordering::Less),
            (_, Eqv) => Some(Ordering::Greater),
            (Read, Write) => None,
            (Write, Read) => None,
            (Read, Add) => None,
            (Add, Read) => None,
            (Read, ReadAdd) => Some(Ordering::Less),
            (ReadAdd, Read) => Some(Ordering::Greater),
            (Read, ReadWrite) => Some(Ordering::Less),
            (ReadWrite, Read) => Some(Ordering::Greater),
            (Write, Add) => None,
            (Add, Write) => None,
            (Write, ReadWrite) => Some(Ordering::Less),
            (ReadWrite, Write) => Some(Ordering::Greater),
            (Add, ReadAdd) => Some(Ordering::Less),
            (ReadAdd, Add) => Some(Ordering::Greater),
            // Because Read + Write can simulate Add,
            // and we want to promote the usage of Add,
            // it is the case that ReadWrite >= Add.
            (Add, ReadWrite) => Some(Ordering::Less),
            (ReadWrite, Add) => Some(Ordering::Greater),
            // In theory Write and ReadAdd should be comparable.
            // Assuming that we allow for using Add on the selected set of types
            // (for which there exists a Monoid instace) it shuold be the case
            // that Read + Add == Write (reading and modifying should be a write).
            // Examples:
            // 1)                  | 2)
            // init_value = 10     | init_value = 10
            // Read + Add(2) = 12  | Read + Add(-2) = 8
            // Write(12)           | Write(8)
            // The problem is that we haven't yet figured out how to accomodate
            // for "negative" operations. Especially how to encode entry removal
            // from a map using an Add.
            // For the safety and correctness reasons I've chosen to make these
            // operations uncomperable.
            (Write, ReadAdd) => None,
            (ReadAdd, Write) => None,
            (ReadAdd, ReadWrite) => None,
            (ReadWrite, ReadAdd) => None,
            (_, _) => {
                // Every enum variant is equal to itself.
                if mem::discriminant(self) == mem::discriminant(other) {
                    Some(Ordering::Equal)
                } else {
                    None
                }
            }
        }
    }
}

impl PartialEq for AccessRights {
    fn eq(&self, other: &AccessRights) -> bool {
        mem::discriminant(self) == mem::discriminant(other)
    }
}

impl Eq for AccessRights {}

#[repr(C)]
#[derive(PartialEq, Eq, Clone, Copy, Debug, Hash, PartialOrd)]
pub enum Key {
    Account([u8; 20]),
    Hash([u8; 32]),
    URef([u8; 32], AccessRights), //TODO: more bytes?
}

use Key::*;
// TODO: I had to remove Ord derived on the Key enum because
// there is no total ordering of the AccessRights but Ord for the Key
// is required by the collections (HashMap, BTreeMap) so I decided to
// implement it by hand by just ignoring the access rights for the URef
// and falling back to the default Ord for the enum variants (top to bottom).
impl Ord for Key {
    fn cmp(&self, other: &Key) -> Ordering {
        match (self, other) {
            (Account(id_1), Account(id_2)) => id_1.cmp(id_2),
            (Account(_), _) => Ordering::Less,
            (Hash(id_1), Hash(id_2)) => id_1.cmp(id_2),
            (Hash(_), URef(_, _)) => Ordering::Less,
            (Hash(_), Account(_)) => Ordering::Greater,
            (URef(id_1, ..), URef(id_2, ..)) => id_1.cmp(id_2),
            (URef(_, _), Account(_)) => Ordering::Greater,
            (URef(_, _), Hash(_)) => Ordering::Greater,
        }
    }
}

impl Key {
    pub fn to_u_ptr<T>(self) -> Option<UPointer<T>> {
        if let URef(id, access_right) = self {
            Some(UPointer::new(id, access_right))
        } else {
            None
        }
    }

    pub fn to_c_ptr(self) -> Option<ContractPointer> {
        match self {
            URef(id, rights) => Some(ContractPointer::URef(UPointer::new(id, rights))),
            Hash(id) => Some(ContractPointer::Hash(id)),
            _ => None,
        }
    }
}

const ACCOUNT_ID: u8 = 0;
const HASH_ID: u8 = 1;
const UREF_ID: u8 = 2;
pub const UREF_SIZE: usize = 37;

impl ToBytes for Key {
    fn to_bytes(&self) -> Vec<u8> {
        match self {
            Account(addr) => {
                let mut result = Vec::with_capacity(25);
                result.push(ACCOUNT_ID);
                result.append(&mut (20u32).to_bytes());
                result.extend(addr);
                result
            }
            Hash(hash) => {
                let mut result = Vec::with_capacity(37);
                result.push(HASH_ID);
                result.append(&mut hash.to_bytes());
                result
            }
            URef(rf, ..) => {
                //TODO: serialize access rights
                let mut result = Vec::with_capacity(37);
                result.push(UREF_ID);
                result.append(&mut rf.to_bytes());
                result
            }
        }
    }
}
impl FromBytes for Key {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (id, rest): (u8, &[u8]) = FromBytes::from_bytes(bytes)?;
        match id {
            ACCOUNT_ID => {
                let (addr, rem): (Vec<u8>, &[u8]) = FromBytes::from_bytes(rest)?;
                if addr.len() != 20 {
                    Err(Error::FormattingError)
                } else {
                    let mut addr_array = [0u8; 20];
                    addr_array.copy_from_slice(&addr);
                    Ok((Account(addr_array), rem))
                }
            }
            HASH_ID => {
                let (hash, rem): ([u8; 32], &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((Hash(hash), rem))
            }
            UREF_ID => {
                let (rf, rem): ([u8; 32], &[u8]) = FromBytes::from_bytes(rest)?;
                // TODO: deserialize access rights
                Ok((URef(rf, AccessRights::ReadWrite), rem))
            }
            _ => Err(Error::FormattingError),
        }
    }
}

impl FromBytes for Vec<Key> {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (size, rest): (u32, &[u8]) = FromBytes::from_bytes(bytes)?;
        let mut result: Vec<Key> = Vec::with_capacity((size as usize) * UREF_SIZE);
        let mut stream = rest;
        for _ in 0..size {
            let (t, rem): (Key, &[u8]) = FromBytes::from_bytes(stream)?;
            result.push(t);
            stream = rem;
        }
        Ok((result, stream))
    }
}
impl ToBytes for Vec<Key> {
    fn to_bytes(&self) -> Vec<u8> {
        let size = self.len() as u32;
        let mut result: Vec<u8> = Vec::with_capacity(4 + (size as usize) * UREF_SIZE);
        result.extend(size.to_bytes());
        result.extend(self.iter().flat_map(ToBytes::to_bytes));
        result
    }
}

impl AsRef<[u8]> for Key {
    fn as_ref(&self) -> &[u8] {
        match self {
            // TODO: need to distinguish between variants?
            Account(a) => a,
            Hash(h) => h,
            URef(u, ..) => u,
        }
    }
}
