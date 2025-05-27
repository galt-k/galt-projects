use std::fmt;
use crate::include::common::config::{PageId, INVALID_PAGE_ID};

/// Represents a Record ID (RID), identifying a tuple's location in a heap page.
#[derive(Clone, Copy, PartialEq, Debug)]
pub struct Rid {
    page_id: PageId,
    slot_num: u32, // The position of the tuple within that Page's tuple array. 
}

impl Rid {
    /// Creates a new RID with the given page ID and slot number.
    pub fn new(page_id: PageId, slot_num: u32) -> Self {
        Rid { page_id, slot_num }
    }

    /// Creates an RID from a 64-bit integer (high 32 bits: page_id, low 32 bits: slot_num).
    pub fn from_i64(rid: i64) -> Self {
        let page_id = (rid >> 32) as PageId;
        let slot_num = (rid & 0xFFFFFFFF) as u32;
        Rid { page_id, slot_num }
    }

    /// Returns the RID as a 64-bit integer (page_id << 32 | slot_num).
    pub fn get(&self) -> i64 {
        ((self.page_id as i64) << 32) | (self.slot_num as i64)
    }

    /// Returns the page ID.
    pub fn get_page_id(&self) -> PageId {
        self.page_id
    }

    /// Returns the slot number.
    pub fn get_slot_num(&self) -> u32 {
        self.slot_num
    }

    /// Sets the page ID and slot number.
    pub fn set(&mut self, page_id: PageId, slot_num: u32) {
        self.page_id = page_id;
        self.slot_num = slot_num;
    }

    /// Returns a string representation of the RID (e.g., "(page_id, slot_num)").
    pub fn to_string(&self) -> String {
        format!("({}, {})", self.page_id, self.slot_num)
    }
}

// Implement Display for std::fmt::Display (equivalent to C++ operator<<)
impl fmt::Display for Rid {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.to_string())
    }
}

// Default implementation for Rid (equivalent to C++ default constructor)
impl Default for Rid {
    fn default() -> Self {
        Rid {
            page_id: INVALID_PAGE_ID,
            slot_num: 0,
        }
    }
}