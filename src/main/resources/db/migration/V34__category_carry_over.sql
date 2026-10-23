-- A category with carry-over is an envelope: its rest moves to the next month. By default a plan is for its month only.
alter table categories add column carry_over boolean not null default false;
