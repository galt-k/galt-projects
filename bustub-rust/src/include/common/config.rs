pub type FrameId = i32;
pub type PageId = i32;
pub const PAGE_SIZE: usize = 4096;
pub enum AccessType {
    Unknown = 0,
    Lookup = 1,
    Scan = 2,
    Index = 3,
}
