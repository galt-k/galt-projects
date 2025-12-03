use std::{
    collections::{BTreeMap, HashMap},
    fs::File,
    io::{BufRead, BufReader},
};



fn main() {
    let f = File::open("measurements.txt").unwrap();
    let f = BufReader::new(f);
    let mut stats = HashMap::<Vec<u8>, (f64, f64, usize, f64)>::new();
    for line in f.split(b'\n') {
        let line = line.unwrap();
        let mut fields = line.rsplitn(2, |c| *c == b';');
        let temperature = fields.next().unwrap();
        let station = fields.next().unwrap();
        // SAFETY: the README promised
        let temperature: f64 = unsafe { std::str::from_utf8_unchecked(temperature) }
            .parse()
            .unwrap();
        let stats = match stats.get_mut(station) {
            Some(stats) => stats,
            None => stats
                .entry(station.to_vec())
                .or_insert((f64::MAX, 0., 0, f64::MIN)),
        };
        stats.0 = stats.0.min(temperature);
        stats.1 += temperature;
        stats.2 += 1;
        stats.3 = stats.3.max(temperature);
    }
    print!("{{");
    let stats = BTreeMap::from_iter(
        stats
            .into_iter()
            // SAFETY: the README promised
            .map(|(k, v)| (unsafe { String::from_utf8_unchecked(k) }, v)),
    );
    let mut stats = stats.into_iter().peekable();
    while let Some((station, (min, sum, count, max))) = stats.next() {
        print!("{station}={min:.1}/{:.1}/{max:.1}", sum / (count as f64));
        if stats.peek().is_some() {
            print!(", ");
        }
    }
    print!("}}");
}