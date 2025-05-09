use crate::include::common::config::IndexPageType;

pub struct BplusTreePage {
    page_type: IndexPageType, // Enumeration of possible page types
    size_: i32, // Number of Key-valur pairs in a page
    max_size: i32, // Max no.of key-value pairs in a page 
}

pub trait BplusTreePageImpl {
    fn is_leaf_page(&self) -> bool;
    fn set_page_type(&self, page_type: IndexPageType);
    fn get_size(&self) -> i32;
    fn set_size(&self, size: i32);
    fn change_size_by(amount: i32);
    fn get_max_size(&self)-> i32;
    fn set_max_size(&self, max_size: i32);
    fn get_min_size(&self) -> i32;    
}