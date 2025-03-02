use std::collections::HashMap;

use crate::include::common::config::FrameId;

struct LRUKNode{

}

struct LRUKReplacer {
    node_store: HashMap<FrameId, LRUKNode>,
}