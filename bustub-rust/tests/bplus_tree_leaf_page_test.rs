use bustub_rust::include::storage::page::b_plus_tree_leaf_page::{BplusTreeLeafPage, BplusTreeLeafPageImpl, LEAF_PAGE_SLOT_CNT};
use bustub_rust::include::common::config::{IndexPageType, PAGE_SIZE, INVALID_PAGE_ID,ValueType};
use bustub_rust::include::common::rid::Rid;

// Test new leaf page creation.  
// Test the maximum leaf insert Key count. 


#[test]
fn test_new_leaf_page() {
    // 1. Create a new leaf page
    let max_size = 100;
    let page_id = 100;
    let leaf_page = BplusTreeLeafPage::new(max_size, page_id);

    // 2. Check the attributes
    assert_eq!(leaf_page.base_page.page_type, IndexPageType::LEAF_PAGE);
    assert!(leaf_page.is_leaf());
    assert_eq!(leaf_page.next_page_id, INVALID_PAGE_ID);
    assert_eq!(leaf_page.key_array.len(), leaf_page.rid_array.len(), "Both the length must match"); 
    assert_eq!(LEAF_PAGE_SLOT_CNT, 255, "values should match");
}


#[test]
fn test_max_key_values() {
    // 1. Generate keyvalues up to max leaf count. 
    // iterate up to leafpage count and insert 
    // Print a message saying that how many have been inserted. 

    // 1. Create a new leaf page
    let max_size = 3;
    let page_id = 100;
    let mut leaf_page = BplusTreeLeafPage::new(max_size, page_id);

    let key = 42;
    let rid = Rid::new(1, 0);
    let value = ValueType::Rid(Rid::new(1, 0)); // ValueType is Rid


    for index in 0 .. max_size {
        assert!(leaf_page.insert(index, key + 1, value));
    }
    
    // checking if the insert returns falase after 
    // inserting the max size 
    assert!(!leaf_page.insert(max_size, 100, value));

}
