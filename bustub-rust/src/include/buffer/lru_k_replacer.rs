use crate::include::common::config::{AccessType, FrameId};

pub trait LRUKReplacer {
    fn new(num_frames: usize, k: usize) -> Self;
    fn evict(&self) -> Option<FrameId>;
    fn record_access(&self, frame_id: FrameId, access_type: AccessType);
    fn set_evictable(&self, frame_id: FrameId, set_evictable: bool);
    fn remove(&self, frame_id: FrameId);
    fn size(&self) -> usize;
}
