use crate::include::common::config::{AccessType, FrameId, PageId};

pub struct FrameHeader {
    frame_id: FrameId,
    page_id: PageId,
    pin_count: integer,
    is_dirty: bool,
    data: string // Sequnce of bytes
}

pub trait FrameHeaderImpl {
    // Return a read-only view of the frame's raw memory as bytes.  
    fn get_data(&self) -> &[u8];
    // Return a mutable view of the frame's raw memory, so the content can be updated.
    fn get_data_mut(&mut self) -> &mut [u8];
    // Clear or reset the frame's content
    fn reset(&mut self);
    // return the page_id that the frame is currently holding
    fn get_page_id(&self) -> PageId;
    // Set the new page_id that the frame has to hold
    fn set_page_id(&self) -> PageId;
    // get the current pin count of the frame
    fn get_pin_count(&self) -> usize;
    // increment pin count
    fn increment_pin_count(&self);
    // decrement pin count
    fn decrement_pin_count(&self);
    // True if the frame has been recently modified and needs to be written back to disk. 
    fn is_dirty(&self) -> bool;
    // True if the frame has been recently modified and needs to be written back to disk. 
    fn set_is_dirty(&self, is_dirty: bool);
    // Acquire a read latch to safely read the frame concurrently. 
    fn read_latch(&self) -> Box<dyn Any>;
    // Acquire a write latch (exclusive access) to modify the frame without reference. 
    fn write_latch(&self) -> Box<dyn Any>;
}

pub trait BufferPoolManagerImpl {
    fn size(&self)-> usize; 
    fn new_page(&self) -> PageId;
    fn delete_page(&self, page_id: PageId) -> bool;
    fn checked_write_page(&self, page_id: PageId, access_type: AccessType) -> Option<WritePageGuard>;
    fn checked_read_page(&self, page_id: PageId, access_type: AccessType) -> Option<ReadPageGuard>;
    fn write_page(&self, page_id: PageId, access_type: AccessType) -> WritePageGuard;
    fn read_page(&self, page_id: PageId, access_type: AccessType) -> ReadPageGuard;
    fn flush_page_unsafe(&self, page_id: PageId) -> bool;
    fn flush_page(&self, page_id: PageId) -> bool;
    fn flush_all_pages_unsafe(&self);
    fn flush_all_pages(&self);
    fn get_pin_count(&self, page_id: PageId) -> Option<usize>;

}