use crate::include::common::config::{ FrameId, AccessType };


pub trait LRUKReplacer {
    fn new(num_frames: usize, k: usize) -> Self;
    fn evict(&mut self) -> Option<FrameId>;
    fn record_access(&mut self, frame_id: FrameId, access_type: AccessType);
    fn set_evictable(&mut self, frame_id: FrameId, set_evictable: bool);
    fn remove(&mut self, frame_id: FrameId);
    fn size(&self) -> usize;
}