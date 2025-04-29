use std::sync::{Arc, RwLock}; 
use std::ops::{Deref, DerefMut}; 
use crate::include::common::config::{AccessType, FrameId, PageId};


pub trait PageguardImpl{
    fn get_page_id(&self) -> PageId;
    fn get_frame_id(&self) -> FrameId;
    fn drop_guard(&mut self); 
}

pub trait ReadPageGuardImpl: PageguardImpl{
    fn as_ref(&self) -> &[u8]; 
    fn is_dirty(&self) -> bool;
    fn flush(&self);
}

pub trait WritePageGuardImpl: PageguardImpl{
    fn as_ref(&self) -> &[u8];
    fn as_mut(&mut self) -> &mut [u8]; 
    fn is_dirty(&self) -> bool;
    fn flush(&self);
}