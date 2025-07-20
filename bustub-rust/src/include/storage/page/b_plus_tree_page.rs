use crate::include::common::config::{IndexPageType, PageId};


#[repr(C)]
#[derive(Debug, PartialEq)]
pub struct BplusTreePage {
    pub page_type: IndexPageType, // Enumeration of possible page types
    pub size_: i32, // Number of Key-valur pairs in a page
    pub max_size: i32, // Max no.of key-value pairs in a page 
    pub page_id: PageId
}

pub trait BplusTreePageImpl {
    fn is_leaf_page(&self) -> bool;
    fn set_page_type(&mut self, page_type: IndexPageType);
    fn get_size(&self) -> i32;
    fn set_size(&mut self, size: i32);
    fn change_size_by(&mut self, amount: i32);
    fn get_max_size(&self)-> i32;
    fn set_max_size(&mut self, max_size: i32);
    fn get_min_size(&self) -> i32;    
}

pub trait BplusTreePageTrait {
    fn is_leaf(&self) -> bool;
    fn max_size(&self) -> i32;
    fn get_size(&self) -> i32;
}