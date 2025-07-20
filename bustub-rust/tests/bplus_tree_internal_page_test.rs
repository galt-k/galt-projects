use bustub_rust::include::storage::page::b_plus_tree_internal_page::{BplusTreeInternalPage, BplusTreeInternalPageImpl, INTERNAL_PAGE_SLOT_CNT};
use bustub_rust::include::common::config::{IndexPageType, PAGE_SIZE, INVALID_PAGE_ID};
use bustub_rust::include::storage::page::b_plus_tree_page::{BplusTreePage, BplusTreePageImpl};

// Test new internal page creation.  
// Test the maximum insert insert Key count. 


#[test]
fn test_new_internal_page() {
    let max_size = 3;
    let page_id = 100;
    // 1. Create a new internal page
    let internal_page = BplusTreeInternalPage::new(max_size, page_id);

    // 2. Check the attributes
    assert_eq!(internal_page.base_page.page_type, IndexPageType::INTERNAL_PAGE);
    assert!(!internal_page.is_leaf());
    assert_eq!(internal_page.key_array.len(), internal_page.page_id_array.len(), "Both the values should match");
    assert_eq!(INTERNAL_PAGE_SLOT_CNT, 340, "match");

    assert_eq!(internal_page.base_page.get_size(), 0);
    assert_eq!(internal_page.base_page.max_size, max_size);
    assert_eq!(internal_page.base_page.page_id, page_id);
    assert_eq!(internal_page.key_array.len(), INTERNAL_PAGE_SLOT_CNT as usize);
    assert_eq!(internal_page.page_id_array.len(), INTERNAL_PAGE_SLOT_CNT as usize);
    assert_eq!(internal_page.key_array, [-1; INTERNAL_PAGE_SLOT_CNT]);
    assert_eq!(internal_page.page_id_array, [INVALID_PAGE_ID; INTERNAL_PAGE_SLOT_CNT as usize]);
}

#[test]
fn test_key_at(){

    let max_size = 3;
    let page_id = 100;
    // 1. Create a new internal page
    let mut internal_page = BplusTreeInternalPage::new(max_size, page_id);
    let index  = 0;
    let key = 45;
    internal_page.set_key_at(0, key);
    internal_page.set_page_id_at(0, page_id);
    //println!("{:?}",internal_page.key_array);
    assert_eq!(internal_page.key_at(index), key, "Both the values should match");
    assert_eq!(internal_page.page_id_value_at(index), page_id);
}

#[test]
fn test_max_key_values() {
    // 1. Generate keyvalues up to maxkey count in an internal page. 

}
