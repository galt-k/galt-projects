#![feature(portable_simd)]
#![feature(slice_split_once)]
#![feature(hasher_prefixfree_extras)]

use std::{
    borrow::Borrow, //? 
    collections::{BTreeMap,HashMap},
    ffi::{c_int, c_void},
    fs::File,
    hash::{BuildHasher, Hash, Hasher},
    os::fd::AsRawFd,
    simd::{cmp::SimdPartialEq, u8x16},
};

const INLINE: usize = 16; // 16bytes long
const LAST: usize = INLINE - 1; // last byte

union StrVec{
    inlined: [u8; INLINE],
    // if the length high bit is set, then inlined 
    // into pointer then len
    // otherwise, pointer is a pointer to Vec<u8>
    heap: (usize, *mut u8),
}

impl StrVec {
    pub fn new(s: &[u8]) -> Self {
        if s.len() < INLINE {
            let mut combined = [0u8; INLINE];
            combined[..s.len()].copy_from_slice(s);
            combined[LAST] = s.len() as u8 + 1;
            Self {
                inlined: combined
            }
        } else {
            let ptr = Box::into_raw(s.to_vec().into_boxed_slice());
            Self {
                heap: (ptr.len().to_be(), ptr as *mut u8),
            }
        }
    }
}

impl Drop for StrVec {
    fn drop(&mut self) {
        if unsafe { self.inlined[LAST] } == 0x00 {
            unsafe {
                let len = usize::from_be(self.heap.0);
                let ptr = self.heap.1;
                let slice_ptr = std::ptr::slice_from_raw_parts_mut(ptr, len);
                let _ = Box::from_raw(slice_ptr);
            }
        }
    }
}

impl AsRef<[u8]> for StrVec {
    fn as_ref(&self) -> &[u8] {
        unsafe {
            if self.inlined[LAST] != 0x00 {
                let len = self.inlined[LAST] as usize - 1;
                std::slice::from_raw_parts(self.inlined.as_ptr(), len)
            } else {
                let len = usize::from_be(self.heap.0);
                let ptr = self.heap.1;
                std::slice::from_raw_parts(ptr, len)                
            } 
        }
    }
}

impl PartialEq for StrVec {
    fn eq(&self, other: &Self) -> bool {
        self.as_ref() == other.as_ref()
    }
}

impl Eq for StrVec {}

impl Hash for StrVec {
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.as_ref().hash(state)        
    }
}

impl Borrow<[u8]> for StrVec {
    fn borrow(&self) -> &[u8] {
        self.as_ref()
    }
}

const SEMI: u8x16 = u8x16::splat(b';');

struct FastHasherBuilder;
struct FastHasher(u64);

impl BuildHasher for FastHasherBuilder {
    type Hasher = FastHasher;
    fn build_hasher(&self) -> Self::Hasher {
        FastHasher(0xcbf29ce484222325)
    }
}

impl Hasher for FastHasher {
    fn finish(&self) -> u64 {        
        self.0 ^ self.0.rotate_right(33) ^ self.0.rotate_right(15)        
    }
    fn write_length_prefix(&mut self, _len: usize) {}

    fn write(&mut self, bytes: &[u8]) {
        let mut word = [0u64; 2];

        unsafe {
            std::ptr::copy(bytes.as_ptr(),
            word.as_mut_ptr().cast::<u8>(),
            bytes.len().min(16),
        )
        };

        self.0 = word[0] ^ word[1];
        // let (chunks, remainder) = bytes.as_chunks::<8>();
        // let mut last = [1u8; 8];
        // (last[..remainder.len()]).copy_from_slice(remainder);
        // for &chunk in chunks.iter().chain(std::iter::once(&last)) {
        //     let mixed = self.0 as u128 * (u64::from_ne_bytes(chunk) as u128);
        //     self.0 = (mixed >> 64) as u64 ^ mixed as u64;
        // }
    }
}


fn main() {
    let f = File::open("measurements.txt").unwrap();
    let map = mmap(&f);
    // initializing a hashmap
    let mut stats = HashMap::<StrVec, (i16, i64, usize, i16),_>::with_capacity_and_hasher(
        1_000,
        FastHasherBuilder,
    );

    let mut at = 0;
    loop {
        let line = next_line(map, &mut at);
        if line.is_empty() {
            break;
        }
        let (station, temperature) = split_semi(line);
        
        let t = parse_temperature(temperature);
        let stats = match stats.get_mut(station) {
            Some(stats) => stats,
            None => stats
                .entry(StrVec::new(station))
                .or_insert((i16::MAX, 0, 0, i16::MIN)),
        };
        stats.0 = stats.0.min(t);
        stats.1 += i64::from(t);
        stats.2 += 1;
        stats.3 = stats.3.max(t);
    }
    print!("{{");
    let stats = BTreeMap::from_iter(
        stats.iter()
            .map(|(k, v)| (unsafe {std::str::from_utf8_unchecked(k.as_ref()) }, *v)),
    );
    let mut stats = stats.into_iter().peekable();
    while let Some((station, (min, sum, count, max))) = stats.next() {
        print!("{station}={:.1}/{:.1}/{:.1}", 
            (min as f64)/10.,
            (sum as f64)/10. / (count as f64),
            (max as f64)/10.
        );
        if stats.peek().is_some() {
            print!(", ");
        }
    }
    print!("}}");
}
//Instead of reading file into a buffer, mmap tells the O.S to make file's bytes appear as if 
// they are already in memory
#[inline]
fn next_line<'a>(map: &'a [u8], at: &mut usize) -> &'a [u8] {
    let rest = &map[*at..];
    let next_new_line = unsafe {
        libc::memchr(
            rest.as_ptr() as *const c_void,
            b'\n' as c_int,
            rest.len())
    };

    if next_new_line.is_null() {
        *at += rest.len();
        rest
    } else {
        // memchr always returns pointers in rest, which are valid
        let len = unsafe {
            (next_new_line as *const u8).offset_from(rest.as_ptr())
        } as usize;
        *at += len + 1;
        &rest[..len]
    }
}


fn mmap(f: &File) -> &'_ [u8] {
    let len = f.metadata().unwrap().len();
    unsafe {
        let ptr = libc::mmap(
            std::ptr::null_mut(),
            len as libc::size_t,
            libc::PROT_READ,
            libc::MAP_SHARED,
            f.as_raw_fd(),
            0,
        );
        // By calling madvice with MADV_SEQUENTIAL, i'm telling Kernel
        // I'm going to read the file from the beginning to end in a straight line.
        // Dont wait for me to hit a Page Fault- Start pre-loading the next chunks 
        // of data from the disk into RAM right now. 
        if ptr == libc::MAP_FAILED {
            panic!("{:?}", std::io::Error::last_os_error());
        } else {
            if libc::madvise(ptr, len as libc::size_t, libc::MADV_SEQUENTIAL) != 0 {
                panic!("{:?}", std::io::Error::last_os_error())
            }
            std::slice::from_raw_parts(ptr as *const u8, len as usize) 
        }

    }
}

#[inline(always)]
fn split_semi(line: &[u8]) -> (&[u8], &[u8]) {
    let len = line.len();
    
    // If the line is at least 16 bytes, we do a "Naked Load"
    // This avoids the 36-second 'load_select_ptr' bottleneck from your trace.
    if len >= 16 {
        let chunk = unsafe {
            // SAFETY: We are reading 16 bytes from a valid mmap slice.
            u8x16::from_slice(std::slice::from_raw_parts(line.as_ptr(), 16))
        };
        let mask = chunk.simd_eq(SEMI).to_bitmask();
        
        if mask != 0 {
            let index = mask.trailing_zeros() as usize;
            return (&line[..index], &line[index + 1..]);
        }
    }

    // Fallback for lines < 16 bytes OR if ';' wasn't in the first 16 bytes.
    // This uses the optimized assembly inside the standard library.
    line.split_once(|&b| b == b';').unwrap()
}
// fn split_semi(line: &[u8]) -> (&[u8], &[u8]) {
//     // line is at most 106B -> 100 + 1 + 5
//     if line.len() > 16 {
//         line.rsplit_once(|c| *c == b';').unwrap()
//     } else {
//         // SAFETY: load_or_default is safe but slow (see your trace).
//         // It's the "safest" way to handle lines shorter than 16 bytes.
//         let delim_eq = SEMI.simd_eq(u8x16::load_or_default(line));
        
//         // Use to_bitmask() for better compatibility with M1/NEON.
//         let mask = delim_eq.to_bitmask();
        
//         if mask != 0 {
//             let index_of_delim = mask.trailing_zeros() as usize;
//             // Double check index is within the slice to prevent slicing errors
//             (&line[..index_of_delim], &line[index_of_delim + 1..])
//         } else {
//             // Edge case: if for some reason SIMD missed it (shouldn't happen in 1BRC)
//             line.split_once(|&c| c == b';').expect("Semicolon missing in short line")
//         }
//     }
// }

fn parse_temperature(temperature: &[u8])-> i16 {
    let mut t : i16 = 0;
    let mut mul = 1;
    for &d in temperature.iter().rev() {
        match d {
            b'.' => {
                continue;
            }
            b'-' => {
                t = -t;
                break;
            }
            _ => {
                t += i16::from(d - b'0') * mul;
                mul *= 10;
            }
        }

    }
    t
}