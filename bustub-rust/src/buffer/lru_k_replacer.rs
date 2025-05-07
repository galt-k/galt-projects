use rand::Rng;
use std::collections::{HashMap, LinkedList};
use std::sync::Mutex;
use std::usize;

use crate::include::buffer::lru_k_replacer::LRUKReplacer;
use crate::include::common::config::{AccessType, FrameId};
use std::cell::RefCell;

pub struct LRUKNode {
    history: LinkedList<usize>,
    k_: usize,
    frame_id: FrameId,
    is_evictable: bool,
}

pub struct LRUKReplacerImpl {
    node_store_: RefCell<HashMap<FrameId, LRUKNode>>,
    curr_size_: RefCell<usize>,
    replacer_size_: usize,
    k_: usize,
    latch_: Mutex<()>,
    current_timestamp_: RefCell<usize>,
}

impl LRUKReplacer for LRUKReplacerImpl {
    fn new(num_frames: usize, k: usize) -> Self {
        LRUKReplacerImpl {
            node_store_: RefCell::new(HashMap::new()),
            curr_size_: RefCell::new(0),
            replacer_size_: num_frames,
            k_: k,
            latch_: Mutex::new(()),
            current_timestamp_: RefCell::new(0),
        }
    }

    fn evict(&self) -> Option<FrameId> {
        let _gaurd = self.latch_.lock().unwrap();
        // let random_frame_id = rand::thread_rng().gen_range(0..self.replacer_size_) as FrameId;
        // Some(random_frame_id)

        let mut min_frame_id: Option<FrameId> = None;
        let mut min_kth_time: usize = usize::MAX;
        for (&frame_id, node) in self.node_store_.borrow().iter() {
            if node.is_evictable {
                if let Some(&kth_time) = node.history.front() {
                    if min_frame_id.is_none() || kth_time < min_kth_time {
                        min_frame_id = Some(frame_id);
                        min_kth_time = kth_time;
                    }
                }
            }

            // frame_id is None {
            //     frame_id = frame_id
            //     let min_global_time = node.history.front();
            // } else {
            //     min_curr_time = MIN(min_global_time, node.history.front());
            //     if min_curr_time < min_global_time {
            //         min_frame_id = frame_id;
            //         min_global_time = min_curr_time;
            //     }

            // }
        }
        min_frame_id
    }

    fn record_access(&self, frame_id: FrameId, access_type: AccessType) {
        // TODO:
        let _gaurd = self.latch_.lock().unwrap();
        let mut node_store = self.node_store_.borrow_mut();
        let mut timestamp = self.current_timestamp_.borrow_mut(); 
        let node = node_store.entry(frame_id).or_insert(LRUKNode {
            history: LinkedList::new(),
            k_: self.k_,
            frame_id: frame_id,
            is_evictable: false,
        });
        node.history.push_back(*timestamp);
        if node.history.len() > node.k_ {
            node.history.pop_front();
        }
        *timestamp += 1;
    }

    fn set_evictable(&self, frame_id: FrameId, set_evictable: bool) {
        // get the LRUK node from the map
        let _gaurd = self.latch_.lock().unwrap();
        let mut node_store = self.node_store_.borrow_mut();
        let mut curr_size = self.curr_size_.borrow_mut();
        if let Some(node) = node_store.get_mut(&frame_id) {
            if node.is_evictable != set_evictable {
                node.is_evictable = set_evictable;
                if set_evictable {
                    *curr_size += 1;
                } else {
                    *curr_size -= 1;
                }
            }
        }
    }

    fn remove(&self, frame_id: FrameId) {
        // Here I think, we might need to deal with memory cleanup or deleting the map for the frame ID
        let _gaurd = self.latch_.lock().unwrap();
        let mut node_store = self.node_store_.borrow_mut();
        let mut curr_size = self.curr_size_.borrow_mut();
        if let Some(node) = node_store.remove(&frame_id) {
            if node.is_evictable {
                *curr_size -= 1
            }
        }
    }

    fn size(&self) -> usize {
        let _gaurd = self.latch_.lock().unwrap();
        *self.curr_size_.borrow()
    }
}
