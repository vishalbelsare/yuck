array [1..10] of var 1..10: x;

constraint max(x) = 1;

solve satisfy;

output ["x = ", show(x)];
