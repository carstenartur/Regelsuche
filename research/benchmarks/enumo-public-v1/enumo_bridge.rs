//! Thin source-only protocol for the pinned, unmodified Enumo rational domain.
//! Copy to upstream src/bin/regelsuche_peer.rs; the README documents the build.
#[path = "../../tests/rational.rs"]
mod original;
pub use original::{rational_best, rational_replicate};

use egg::{CostFunction, Extractor, Id, Language};
use original::Math;
use ruler::{enumo::Ruleset, EGraph, SynthAnalysis, SynthLanguage};
use serde_json::{json, Value};
use std::io::{self, BufRead, Write};
use std::time::{Duration, Instant};

struct SurfaceCost;
impl CostFunction<Math> for SurfaceCost {
    type Cost = usize;
    fn cost<C>(&mut self, node: &Math, mut costs: C) -> usize
    where C: FnMut(Id) -> usize {
        match node {
            Math::Lit(value) => usize::from(*value.denom() != 1) + usize::from(*value.numer() < 0),
            Math::Var(_) => 0,
            Math::Abs(_) | Math::If(_) => usize::MAX,
            _ => node.fold(1, |sum, child| sum.saturating_add(costs(child))),
        }
    }
}

fn prepare_graph(expression: &egg::RecExpr<Math>) -> (EGraph<Math, SynthAnalysis>, Id) {
    let mut graph = EGraph::<Math, SynthAnalysis>::default();
    // Follow upstream Workload::to_egraph: initialize the original domain's
    // variable samples in source order before any compound expression is added.
    // Its applier uses these samples to reject undefined intermediate terms.
    let mut variables = Vec::new();
    for node in expression.as_ref() {
        if let Math::Var(name) = node {
            let name = name.to_string();
            if !variables.contains(&name) { variables.push(name); }
        }
    }
    if variables.is_empty() {
        // The original initialize_vars indexes its first variable. A constant
        // expression needs one sample, but no synthetic variable in its graph.
        graph.analysis.cvec_len = 1;
    } else {
        Math::initialize_vars(&mut graph, &variables);
    }
    let root = graph.add_expr(expression);
    graph.rebuild();
    (graph, root)
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = std::env::args().collect();
    if args.len() != 3 { return Err("expected acquire|worker and rules path".into()); }
    if args[1] == "acquire" {
        let start = Instant::now();
        let rules = original::rational_best::best_enumo_recipe();
        let nanos = start.elapsed().as_nanos();
        rules.to_file(&args[2]);
        let receipt = json!({"status":"ACQUIRED", "recipe":"original::rational_best::best_enumo_recipe",
            "rules":rules.len(), "bidirectionalRules":rules.bidir_len(), "acquisitionNanos":nanos});
        std::fs::write(format!("{}.receipt.json", args[2]), serde_json::to_vec_pretty(&receipt)?)?;
        return Ok(());
    }
    if args[1] != "worker" { return Err("unknown mode".into()); }
    let rules = Ruleset::<Math>::from_file(&args[2]);
    println!("{}", json!({"status":"READY", "rules":rules.len()}));
    io::stdout().flush()?;
    for line in io::stdin().lock().lines() {
        let line = line?;
        if line.len() > 16_384 { return Err("request too large".into()); }
        let value: Value = serde_json::from_str(&line)?;
        let object = value.as_object().ok_or("request must be object")?;
        if object.len() != 2 || value["op"] != "run" { return Err("unsupported fields or operation".into()); }
        let source = value["source"].as_str().ok_or("source missing")?;
        let expression: egg::RecExpr<Math> = source.parse()?;
        let (graph, root) = prepare_graph(&expression);
        let start = Instant::now();
        // Actual egg executes the rules returned by the original Enumo recipe.
        // The parent supervisor applies the common two-second request + judge limit.
        let runner = egg::Runner::default().with_egraph(graph)
            .with_iter_limit(5).with_node_limit(100_000)
            .with_time_limit(Duration::from_millis(1_800))
            .run(rules.0.values().map(|rule| &rule.rewrite));
        let (cost, output) = Extractor::new(&runner.egraph, SurfaceCost).find_best(root);
        println!("{}", json!({"status":"CANDIDATE", "source":source, "output":output.to_string(),
            "cost":cost, "nativeNanos":start.elapsed().as_nanos(),
            "nodes":runner.egraph.total_size(), "iterations":runner.iterations.len(),
            "stopReason":format!("{:?}", runner.stop_reason)}));
        io::stdout().flush()?;
    }
    Ok(())
}

#[cfg(test)]
mod bridge_tests {
    use super::*;

    #[test]
    fn initializes_original_domain_before_adding_expressions() {
        let expression = "(- (* x1 x2) (* 2 x3))".parse().unwrap();
        let (mut graph, root) = prepare_graph(&expression);
        assert!(!graph[root].data.cvec.is_empty(), "the original domain requires variable samples");
        let undefined = graph.add_expr(&"(/ 0 0)".parse().unwrap());
        assert!(!graph[undefined].data.is_defined(), "undefined intermediates must not be rewritten");
    }

    #[test]
    fn constant_requests_also_reject_undefined_intermediates() {
        let expression = "(+ 1 2)".parse().unwrap();
        let (mut graph, _) = prepare_graph(&expression);
        let undefined = graph.add_expr(&"(/ 0 0)".parse().unwrap());
        assert!(!graph[undefined].data.is_defined());
    }
}
