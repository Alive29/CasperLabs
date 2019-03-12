//! Core types for a Merkle Trie

use common::bytesrepr::{self, FromBytes, ToBytes};
use shared::newtypes::Blake2bHash;
use std::ops::Deref;

const RADIX: usize = 256;

/// Represents a pointer to the next object in a Merkle Trie
#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub enum Pointer {
    LeafPointer(Blake2bHash),
    NodePointer(Blake2bHash),
}

impl Pointer {
    fn hash(&self) -> &Blake2bHash {
        match self {
            Pointer::LeafPointer(hash) => hash,
            Pointer::NodePointer(hash) => hash,
        }
    }

    fn tag(&self) -> u32 {
        match self {
            Pointer::LeafPointer(_) => 0,
            Pointer::NodePointer(_) => 1,
        }
    }
}

impl ToBytes for Pointer {
    fn to_bytes(&self) -> Vec<u8> {
        let mut hash_bytes = self.hash().to_bytes();
        let mut ret = Vec::with_capacity(4 + hash_bytes.len());
        ret.append(&mut self.tag().to_bytes());
        ret.append(&mut hash_bytes);
        ret
    }
}

impl FromBytes for Pointer {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        let (tag, rem): (u32, &[u8]) = FromBytes::from_bytes(bytes)?;
        match tag {
            0 => {
                let (hash, rem): (Blake2bHash, &[u8]) = FromBytes::from_bytes(rem)?;
                Ok((Pointer::LeafPointer(hash), rem))
            }
            1 => {
                let (hash, rem): (Blake2bHash, &[u8]) = FromBytes::from_bytes(rem)?;
                Ok((Pointer::NodePointer(hash), rem))
            }
            _ => Err(bytesrepr::Error::FormattingError),
        }
    }
}

/// Represents the underlying structure of a node in a Merkle Trie
#[derive(Copy, Clone)]
pub struct PointerBlock([Option<Pointer>; RADIX]);

impl PointerBlock {
    pub fn new() -> Self {
        Default::default()
    }
}

impl From<[Option<Pointer>; RADIX]> for PointerBlock {
    fn from(src: [Option<Pointer>; RADIX]) -> Self {
        PointerBlock(src)
    }
}

impl PartialEq for PointerBlock {
    #[inline]
    fn eq(&self, other: &PointerBlock) -> bool {
        self.0[..] == other.0[..]
    }
}

impl Eq for PointerBlock {}

impl Default for PointerBlock {
    fn default() -> Self {
        PointerBlock([Default::default(); RADIX])
    }
}

impl ToBytes for PointerBlock {
    fn to_bytes(&self) -> Vec<u8> {
        ToBytes::to_bytes(&self.0)
    }
}

impl FromBytes for PointerBlock {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        FromBytes::from_bytes(bytes).map(|(arr, rem)| (PointerBlock(arr), rem))
    }
}

impl ::std::ops::Index<usize> for PointerBlock {
    type Output = Option<Pointer>;

    #[inline]
    fn index(&self, index: usize) -> &Self::Output {
        let PointerBlock(dat) = self;
        &dat[index]
    }
}

impl ::std::ops::IndexMut<usize> for PointerBlock {
    #[inline]
    fn index_mut(&mut self, index: usize) -> &mut Self::Output {
        let PointerBlock(dat) = self;
        &mut dat[index]
    }
}

impl ::std::fmt::Debug for PointerBlock {
    fn fmt(&self, f: &mut ::std::fmt::Formatter) -> ::std::fmt::Result {
        write!(f, "{}(", stringify!(PointerBlock))?;
        for item in self.0[..].iter() {
            write!(f, "{:?}", item)?;
        }
        write!(f, ")")
    }
}

/// Represents a Merkle Trie
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Trie<K, V> {
    Leaf { key: K, value: V },
    Node { pointer_block: Box<PointerBlock> },
    Extension { affix: Vec<u8>, pointer: Pointer },
}

impl<K, V> Trie<K, V> {
    fn tag(&self) -> u32 {
        match self {
            Trie::Leaf { .. } => 0,
            Trie::Node { .. } => 1,
            Trie::Extension { .. } => 2,
        }
    }
}

impl<K: ToBytes, V: ToBytes> ToBytes for Trie<K, V> {
    fn to_bytes(&self) -> Vec<u8> {
        match self {
            Trie::Leaf { key, value } => {
                let mut key_bytes = ToBytes::to_bytes(key);
                let mut value_bytes = ToBytes::to_bytes(value);
                let mut ret: Vec<u8> = Vec::with_capacity(4 + key_bytes.len() + value_bytes.len());
                ret.append(&mut self.tag().to_bytes());
                ret.append(&mut key_bytes);
                ret.append(&mut value_bytes);
                ret
            }
            Trie::Node { pointer_block } => {
                let mut pointer_block_bytes = ToBytes::to_bytes(pointer_block.deref());
                let mut ret: Vec<u8> = Vec::with_capacity(4 + pointer_block_bytes.len());
                ret.append(&mut self.tag().to_bytes());
                ret.append(&mut pointer_block_bytes);
                ret
            }
            Trie::Extension { affix, pointer } => {
                let mut affix_bytes = ToBytes::to_bytes(affix);
                let mut pointer_bytes = ToBytes::to_bytes(pointer);
                let mut ret: Vec<u8> =
                    Vec::with_capacity(4 + affix_bytes.len() + pointer_bytes.len());
                ret.append(&mut self.tag().to_bytes());
                ret.append(&mut affix_bytes);
                ret.append(&mut pointer_bytes);
                ret
            }
        }
    }
}

impl<K: FromBytes, V: FromBytes> FromBytes for Trie<K, V> {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), bytesrepr::Error> {
        let (tag, rem): (u32, &[u8]) = FromBytes::from_bytes(bytes)?;
        match tag {
            0 => {
                let (key, rem): (K, &[u8]) = FromBytes::from_bytes(rem)?;
                let (value, rem): (V, &[u8]) = FromBytes::from_bytes(rem)?;
                Ok((Trie::Leaf { key, value }, rem))
            }
            1 => {
                let (pointer_block, rem): (PointerBlock, &[u8]) = FromBytes::from_bytes(rem)?;
                Ok((
                    Trie::Node {
                        pointer_block: Box::new(pointer_block),
                    },
                    rem,
                ))
            }
            2 => {
                let (affix, rem): (Vec<u8>, &[u8]) = FromBytes::from_bytes(rem)?;
                let (pointer, rem): (Pointer, &[u8]) = FromBytes::from_bytes(rem)?;
                Ok((Trie::Extension { affix, pointer }, rem))
            }
            _ => Err(bytesrepr::Error::FormattingError),
        }
    }
}

#[cfg(test)]
mod tests {
    mod pointer_block {
        use crate::history::trie::*;

        #[test]
        fn assignment_and_indexing() {
            let test_hash = Blake2bHash::new(b"TrieTrieAgain");
            let leaf_pointer = Some(Pointer::LeafPointer(test_hash));
            let mut pointer_block = PointerBlock::new();
            pointer_block[0] = leaf_pointer;
            pointer_block[RADIX - 1] = leaf_pointer;
            assert_eq!(leaf_pointer, pointer_block[0]);
            assert_eq!(leaf_pointer, pointer_block[RADIX - 1]);
            assert_eq!(None, pointer_block[1]);
            assert_eq!(None, pointer_block[RADIX - 2]);
        }

        #[test]
        #[should_panic]
        fn assignment_off_end() {
            let test_hash = Blake2bHash::new(b"TrieTrieAgain");
            let leaf_pointer = Some(Pointer::LeafPointer(test_hash));
            let mut pointer_block = PointerBlock::new();
            pointer_block[RADIX] = leaf_pointer;
        }

        #[test]
        #[should_panic]
        fn indexing_off_end() {
            let pointer_block = PointerBlock::new();
            let _val = pointer_block[RADIX];
        }
    }
}
