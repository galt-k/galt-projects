pub type FrameId = i32;

pub enum AccessType {
    Unknown = 0,
    Lookup = 1,
    Scan = 2,
    Index = 3,
}
