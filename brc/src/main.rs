use std::{collections::HashMap, env::args, io::Read};

struct Record {
    count: u32,
    min: V,
    max: V,
    sum: V,
}

impl Record {
    fn default() -> Self {
        Self {
            count: 0,
            min: i32::MAX,
            max: i32::MIN,
            sum: 0,
        }
    }
    fn add(&mut self, value: V) {
        self.count += 1;
        self.sum += value;
        self.min = self.min.min(value);
        self.max = self.max.max(value);
    }
    fn avg(&self) -> V {
        self.sum / self.count as V
    }
}

type V = i32;

fn parse(mut str_bytes: &[u8]) -> V {
    // Extract the negative out of the string
    // check the first bytes is equal to b'-'
    let mut is_neg = false;
    if str_bytes[0] == b'-'{
        str_bytes = &str_bytes[1..];
        is_neg = true;
    }
    // As there are only few number of patterns possible
    let (a,b,c) = match str_bytes {
        [b, b'.', c] => (0, b - b'0', c - b'0' ),
        [a, b, b'.', c] => (a - b'0', b - b'0', c - b'0' ),
        _ => panic!("Unknown pattern {:?}", std::str::from_utf8(str_bytes).unwrap()),
    };

    let v = a as V * 100 + b as V * 10 + c as V;
    if is_neg {
        -v
    } else {
        v
    }

}

fn format(v: V) -> String {
    format!("{:.1}", v as f64 / 10.0)
}

fn main() {
    let filename = args().nth(1).unwrap_or("measurements.txt".to_string());
    let mut data = vec![];
    {
        let mut file = std::fs::File::open(filename).unwrap();
        file.read_to_end(&mut data).unwrap();
        assert!(data.pop() == Some(b'\n'));
    }
    let mut h = HashMap::new();
    for line in data.split(|&c| c == b'\n') {
        let pos = line.iter().position(|&c| c == b';').expect("No ';' found");
        let name = &line[..pos];
        let value = &line[pos + 1..];
        h.entry(name).or_insert(Record::default()).add(parse(value));
    }

    let mut v = h.into_iter().collect::<Vec<_>>();
    v.sort_unstable_by_key(|p| p.0);
    for (name, r) in &v {
        println!(
            "{}: {}/{}/{}",
            std::str::from_utf8(name).unwrap(),
            format(r.min),
            format(r.avg()),
            format(r.max)
        );
    }
    eprintln!("Num records: {}", v.len());
}