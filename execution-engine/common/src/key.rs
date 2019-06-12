use core::fmt::Write;

use super::alloc::vec::Vec;
use super::bytesrepr::{Error, FromBytes, ToBytes, N32, OPTION_SIZE, U32_SIZE};
use crate::contract_api::pointers::*;
use bitflags;

bitflags! {
    #[allow(clippy::derive_hash_xor_eq)]
    pub struct AccessRights: u8 {
        const READ  = 0b001;
        const WRITE = 0b010;
        const ADD   = 0b100;
        const READ_ADD       = Self::READ.bits | Self::ADD.bits;
        const READ_WRITE     = Self::READ.bits | Self::WRITE.bits;
        const ADD_WRITE      = Self::ADD.bits  | Self::WRITE.bits;
        const READ_ADD_WRITE = Self::READ.bits | Self::ADD.bits | Self::WRITE.bits;
    }
}

impl AccessRights {
    pub fn is_readable(self) -> bool {
        self & AccessRights::READ == AccessRights::READ
    }

    pub fn is_writeable(self) -> bool {
        self & AccessRights::WRITE == AccessRights::WRITE
    }

    pub fn is_addable(self) -> bool {
        self & AccessRights::ADD == AccessRights::ADD
    }
}

impl core::fmt::Display for AccessRights {
    fn fmt(&self, f: &mut core::fmt::Formatter) -> core::fmt::Result {
        match *self {
            AccessRights::READ => write!(f, "READ"),
            AccessRights::WRITE => write!(f, "WRITE"),
            AccessRights::ADD => write!(f, "ADD"),
            AccessRights::READ_ADD => write!(f, "READ_ADD"),
            AccessRights::READ_WRITE => write!(f, "READ_WRITE"),
            AccessRights::ADD_WRITE => write!(f, "ADD_WRITE"),
            AccessRights::READ_ADD_WRITE => write!(f, "READ_ADD_WRITE"),
            _ => write!(f, "UNKNOWN"),
        }
    }
}

pub const LOCAL_SEED_SIZE: usize = 32;
pub const LOCAL_KEY_HASH_SIZE: usize = 32;

#[repr(C)]
#[derive(PartialEq, Eq, PartialOrd, Ord, Clone, Copy, Hash)]
pub enum Key {
    Account([u8; 32]),
    Hash([u8; 32]),
    URef([u8; 32], Option<AccessRights>), //TODO: more bytes?
    Local {
        seed: [u8; LOCAL_SEED_SIZE],
        key_hash: [u8; LOCAL_KEY_HASH_SIZE],
    },
}

// There is no impl LowerHex for neither [u8; 32] nor &[u8] in std.
// I can't impl them b/c they're not living in current crate.
fn addr_to_hex(addr: &[u8; 32]) -> String {
    let mut str = String::new();
    for b in addr {
        write!(&mut str, "{:02x}", b).unwrap();
    }
    str
}

impl core::fmt::Display for Key {
    fn fmt(&self, f: &mut core::fmt::Formatter) -> core::fmt::Result {
        match self {
            Key::Account(addr) => write!(f, "Account({})", addr_to_hex(addr)),
            Key::Hash(addr) => write!(f, "Hash({})", addr_to_hex(addr)),
            Key::URef(addr, Some(access_rights)) => {
                write!(f, "URef({}, {})", addr_to_hex(addr), access_rights)
            }
            Key::URef(addr, None) => write!(f, "URef({}, None)", addr_to_hex(addr)),
            Key::Local { seed, key_hash } => {
                write!(f, "Local({}, {})", addr_to_hex(seed), addr_to_hex(key_hash))
            }
        }
    }
}

impl core::fmt::Debug for Key {
    fn fmt(&self, f: &mut core::fmt::Formatter) -> core::fmt::Result {
        write!(f, "{}", self)
    }
}

use alloc::string::String;
use Key::*;

impl Key {
    pub fn to_u_ptr<T>(self) -> Option<UPointer<T>> {
        if let URef(id, Some(rights)) = self {
            Some(UPointer::new(id, rights))
        } else {
            None
        }
    }

    pub fn to_c_ptr(self) -> Option<ContractPointer> {
        match self {
            URef(id, Some(rights)) => Some(ContractPointer::URef(UPointer::new(id, rights))),
            Hash(id) => Some(ContractPointer::Hash(id)),
            _ => None,
        }
    }

    /// Returns bytes of an account
    pub fn as_account(&self) -> Option<[u8; 32]> {
        match self {
            Account(bytes) => Some(*bytes),
            _ => None,
        }
    }

    pub fn normalize(self) -> Key {
        match self {
            Key::URef(id, _) => Key::URef(id, None),
            other => other,
        }
    }

    /// Creates an instance of [Key::Hash] variant from the base16 encoded String.
    /// Returns `None` if [addr] is not valid Blake2b hash.
    pub fn parse_hash(addr: String) -> Option<Key> {
        let mut buff = [0u8; 32];
        match binascii::hex2bin(addr.as_bytes(), &mut buff) {
            Ok(_) => Some(Key::Hash(buff)),
            _ => None,
        }
    }

    /// Creates an instance of [Key::URef] variant from the base16 encoded String.
    /// Returns `None` if [addr] is not valid Blake2b hash.
    pub fn parse_uref(addr: String, access_rights: AccessRights) -> Option<Key> {
        let mut buff = [0u8; 32];
        match binascii::hex2bin(addr.as_bytes(), &mut buff) {
            Ok(_) => Some(Key::URef(buff, Some(access_rights))),
            _ => None,
        }
    }

    /// Creates an instance of [Key::Local] variant from the base16 encoded String.
    /// Returns `None` if either [seed] or [key_hash] is not valid Blake2b hash.
    pub fn parse_local(seed: String, key_hash: String) -> Option<Key> {
        let mut seed_buff = [0u8; 32];
        let mut key_buff = [0u8; 32];
        match binascii::hex2bin(seed.as_bytes(), &mut seed_buff)
            .and(binascii::hex2bin(key_hash.as_bytes(), &mut key_buff))
        {
            Ok(_) => Some(Key::Local {
                seed: seed_buff,
                key_hash: key_buff,
            }),
            _ => None,
        }
    }
}

const ACCOUNT_ID: u8 = 0;
const HASH_ID: u8 = 1;
const UREF_ID: u8 = 2;
const LOCAL_ID: u8 = 3;

const KEY_ID_SIZE: usize = 1; // u8 used to determine the ID
const ACCESS_RIGHTS_SIZE: usize = 1; // u8 used to tag AccessRights
const ACCOUNT_KEY_SIZE: usize = KEY_ID_SIZE + U32_SIZE + N32;
const HASH_KEY_SIZE: usize = KEY_ID_SIZE + U32_SIZE + N32;
pub const UREF_SIZE: usize = KEY_ID_SIZE + U32_SIZE + N32 + OPTION_SIZE + ACCESS_RIGHTS_SIZE;
const LOCAL_SIZE: usize = KEY_ID_SIZE + U32_SIZE + LOCAL_SEED_SIZE + U32_SIZE + LOCAL_KEY_HASH_SIZE;

impl ToBytes for AccessRights {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        self.bits.to_bytes()
    }
}

impl FromBytes for AccessRights {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (id, rest): (u8, &[u8]) = FromBytes::from_bytes(bytes)?;
        let access_rights = match AccessRights::from_bits(id) {
            Some(rights) => Ok(rights),
            None => Err(Error::FormattingError),
        };
        access_rights.map(|rights| (rights, rest))
    }
}

impl ToBytes for Key {
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        match self {
            Account(addr) => {
                let mut result = Vec::with_capacity(ACCOUNT_KEY_SIZE);
                result.push(ACCOUNT_ID);
                result.append(&mut addr.to_bytes()?);
                Ok(result)
            }
            Hash(hash) => {
                let mut result = Vec::with_capacity(HASH_KEY_SIZE);
                result.push(HASH_ID);
                result.append(&mut hash.to_bytes()?);
                Ok(result)
            }
            URef(rf, maybe_access_rights) => {
                let mut result = Vec::with_capacity(UREF_SIZE);
                result.push(UREF_ID);
                result.append(&mut rf.to_bytes()?);
                result.append(&mut maybe_access_rights.to_bytes()?);
                Ok(result)
            }
            Local { seed, key_hash } => {
                let mut result = Vec::with_capacity(LOCAL_SIZE);
                result.push(LOCAL_ID);
                result.append(&mut seed.to_bytes()?);
                result.append(&mut key_hash.to_bytes()?);
                Ok(result)
            }
        }
    }
}

impl FromBytes for Key {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (id, rest): (u8, &[u8]) = FromBytes::from_bytes(bytes)?;
        match id {
            ACCOUNT_ID => {
                let (addr, rem): ([u8; 32], &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((Account(addr), rem))
            }
            HASH_ID => {
                let (hash, rem): ([u8; 32], &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((Hash(hash), rem))
            }
            UREF_ID => {
                let (rf, rem): ([u8; 32], &[u8]) = FromBytes::from_bytes(rest)?;
                let (maybe_access_rights, rem2): (Option<AccessRights>, &[u8]) =
                    FromBytes::from_bytes(rem)?;
                Ok((URef(rf, maybe_access_rights), rem2))
            }
            LOCAL_ID => {
                let (seed, rest): ([u8; 32], &[u8]) = FromBytes::from_bytes(rest)?;
                let (key_hash, rest): ([u8; 32], &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((Local { seed, key_hash }, rest))
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
    fn to_bytes(&self) -> Result<Vec<u8>, Error> {
        let size = self.len() as u32;
        let mut result: Vec<u8> = Vec::with_capacity(4 + (size as usize) * UREF_SIZE);
        result.extend(size.to_bytes()?);
        result.extend(
            self.iter()
                .map(ToBytes::to_bytes)
                .collect::<Result<Vec<_>, _>>()?
                .into_iter()
                .flatten(),
        );
        Ok(result)
    }
}

#[allow(clippy::unnecessary_operation)]
#[cfg(test)]
mod tests {
    use crate::key::AccessRights;
    use crate::key::Key;
    use alloc::string::String;

    fn test_readable(right: AccessRights, is_true: bool) {
        assert_eq!(right.is_readable(), is_true)
    }

    #[test]
    fn test_is_readable() {
        test_readable(AccessRights::READ, true);
        test_readable(AccessRights::READ_ADD, true);
        test_readable(AccessRights::READ_WRITE, true);
        test_readable(AccessRights::READ_ADD_WRITE, true);
        test_readable(AccessRights::ADD, false);
        test_readable(AccessRights::ADD_WRITE, false);
        test_readable(AccessRights::WRITE, false);
    }

    fn test_writable(right: AccessRights, is_true: bool) {
        assert_eq!(right.is_writeable(), is_true)
    }

    #[test]
    fn test_is_writable() {
        test_writable(AccessRights::WRITE, true);
        test_writable(AccessRights::READ_WRITE, true);
        test_writable(AccessRights::ADD_WRITE, true);
        test_writable(AccessRights::READ, false);
        test_writable(AccessRights::ADD, false);
        test_writable(AccessRights::READ_ADD, false);
        test_writable(AccessRights::READ_ADD_WRITE, true);
    }

    fn test_addable(right: AccessRights, is_true: bool) {
        assert_eq!(right.is_addable(), is_true)
    }

    #[test]
    fn test_is_addable() {
        test_addable(AccessRights::ADD, true);
        test_addable(AccessRights::READ_ADD, true);
        test_addable(AccessRights::READ_WRITE, false);
        test_addable(AccessRights::ADD_WRITE, true);
        test_addable(AccessRights::READ, false);
        test_addable(AccessRights::WRITE, false);
        test_addable(AccessRights::READ_ADD_WRITE, true);
    }

    #[test]
    fn should_display_key() {
        let expected_hash = core::iter::repeat("0").take(64).collect::<String>();
        let addr_array = [0u8; 32];
        let account_key = Key::Account(addr_array);
        assert_eq!(
            format!("{}", account_key),
            format!("Account({})", expected_hash)
        );
        let uref_key = Key::URef(addr_array, Some(AccessRights::READ));
        assert_eq!(
            format!("{}", uref_key),
            format!("URef({}, READ)", expected_hash)
        );
        let hash_key = Key::Hash(addr_array);
        assert_eq!(format!("{}", hash_key), format!("Hash({})", expected_hash));
        let local_key = Key::Local {
            seed: addr_array,
            key_hash: addr_array,
        };
        assert_eq!(
            format!("{}", local_key),
            format!("Local({}, {})", expected_hash, expected_hash)
        );
    }

    use proptest::prelude::*;
    use proptest::string::{string_regex, RegexGeneratorStrategy};

    /// Create a base16 string of [[length]] size.
    fn base16_str_arb(length: usize) -> RegexGeneratorStrategy<String> {
        string_regex(&format!("[0-9a-f]{{{}}}", length)).unwrap()
    }

    proptest! {

        #[test]
        fn should_parse_32_base16_to_key(base16_addr in base16_str_arb(32)) {
            assert!(Key::parse_hash(base16_addr.clone()).is_some());
            assert!(Key::parse_uref(base16_addr.clone(), AccessRights::READ).is_some());
            assert!(Key::parse_local(base16_addr.clone(), base16_addr.clone()).is_some());
        }

        #[test]
        fn should_parse_64_base16_to_key(base16_addr in base16_str_arb(64)) {
            assert!(Key::parse_hash(base16_addr.clone()).is_some());
            assert!(Key::parse_uref(base16_addr.clone(), AccessRights::READ).is_some());
            assert!(Key::parse_local(base16_addr.clone(), base16_addr).is_some());
        }

        #[test]
        fn should_fail_parse_invalid_length_base16_to_key(base16_addr in base16_str_arb(70)) {
            assert!(Key::parse_hash(base16_addr.clone()).is_none());
            assert!(Key::parse_uref(base16_addr.clone(), AccessRights::READ).is_none());
            assert!(Key::parse_local(base16_addr.clone(), base16_addr).is_none());
        }

        #[test]
        fn should_fail_parse_not_base16_input(invalid_addr in "[f-z]{32}") {
            // Only a-f characters are valid hex.
            assert!(Key::parse_hash(invalid_addr.clone()).is_none());
            assert!(Key::parse_uref(invalid_addr.clone(), AccessRights::READ).is_none());
            assert!(Key::parse_local(invalid_addr.clone(), invalid_addr).is_none());
        }

    }
}
