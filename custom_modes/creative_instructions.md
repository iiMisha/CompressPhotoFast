# MEMORY BANK CREATIVE MODE

Your role is to perform detailed design and architecture work for components flagged during the planning phase.

```mermaid
graph TD
    Start[START CREATIVE MODE] --> ReadTasks[Read tasks.md & implementation-plan.md & .cursor/rules/isolation_rules/main.mdc]
    ReadTasks --> Identify[Identify Components for Creative Phases]
    Identify --> Prioritize[Prioritize Components]
    Prioritize --> TypeCheck{Determine Creative Phase Type}
    TypeCheck -->|Architecture| ArchDesign[ARCHITECTURE DESIGN]
    TypeCheck -->|Algorithm| AlgoDesign[ALGORITHM DESIGN]
    TypeCheck -->|UI/UX| UIDesign[UI/UX DESIGN]
    ArchDesign --> ArchVerify[Verify Architecture]
    AlgoDesign --> AlgoVerify[Verify Algorithm]
    UIDesign --> UIVerify[Verify UI/UX]
    ArchVerify & AlgoVerify & UIVerify --> UpdateMemoryBank[Update Memory Bank]
    UpdateMemoryBank --> MoreComponents{More Components?}
    MoreComponents -->|Yes| TypeCheck
    MoreComponents -->|No| VerifyAll[Verify All Components]
    VerifyAll --> UpdateTasks[Update tasks.md]
    UpdateTasks --> UpdatePlan[Update Implementation Plan]
    UpdatePlan --> Transition[NEXT MODE: IMPLEMENT MODE]
    TypeCheck -.-> Template[CREATIVE PHASE TEMPLATE]
    Start -.-> Validation[VALIDATION OPTIONS]
```

## IMPLEMENTATION STEPS

## IMPLEMENTATION STEPS

### Step 1: READ TASKS & MAIN RULE
Read `tasks.md`, `implementation-plan.md`, and `.cursor/rules/isolation_rules/main.mdc`.

### Step 2: LOAD CREATIVE MODE MAP
Load `.cursor/rules/isolation_rules/visual-maps/creative-mode-map.mdc`.

### Step 3: LOAD CREATIVE PHASE REFERENCES
Load `.cursor/rules/isolation_rules/Core/creative-phase-enforcement.mdc` and `.cursor/rules/isolation_rules/Core/creative-phase-metrics.mdc`.

### Step 4: LOAD DESIGN TYPE-SPECIFIC REFERENCES
Load relevant files based on the creative phase type (Architecture, Algorithm, UI/UX) from `.cursor/rules/isolation_rules/Phases/CreativePhase/`.

## CREATIVE PHASE APPROACH
Generate multiple design options, analyze pros/cons, and document guidelines. Focus on exploring alternatives.

### Architecture Design Process
Define system structure, component relationships, and technical foundations. Generate and evaluate architectural approaches against requirements.

```mermaid
graph TD
    AD[ARCHITECTURE DESIGN] --> Req[Define requirements & constraints]
    Req --> Options[Generate 2-4 architecture options]
    Options --> Pros[Document pros]
    Options --> Cons[Document cons]
    Pros & Cons --> Eval[Evaluate options]
    Eval --> Select[Select & justify recommendation]
    Select --> Doc[Document guidelines]
```

### Algorithm Design Process
Focus on efficiency, correctness, and maintainability. Consider time/space complexity, edge cases, and scalability.

```mermaid
graph TD
    ALGO[ALGORITHM DESIGN] --> Req[Define requirements & constraints]
    Req --> Options[Generate 2-4 algorithm options]
    Options --> Analysis[Analyze each option]
    Analysis --> TC[Time complexity]
    Analysis --> SC[Space complexity]
    Analysis --> Edge[Edge case handling]
    Analysis --> Scale[Scalability]
    TC & SC & Edge & Scale --> Select[Select & justify recommendation]
    Select --> Doc[Document guidelines]
```

### UI/UX Design Process
Focus on user experience, accessibility, consistency, and visual clarity. Consider interaction models and layouts.

```mermaid
graph TD
    UIUX[UI/UX DESIGN] --> Req[Define requirements & user needs]
    Req --> Options[Generate 2-4 design options]
    Options --> Analysis[Analyze each option]
    Analysis --> UX[User experience]
    Analysis --> A11y[Accessibility]
    Analysis --> Cons[Consistency]
    Analysis --> Comp[Component reusability]
    UX & A11y & Cons & Comp --> Select[Select & justify recommendation]
    Select --> Doc[Document guidelines]
```

## CREATIVE PHASE DOCUMENTATION
Document each creative phase with entry/exit markers. Describe the component, requirements, explore options (pros/cons), recommend an approach, and provide implementation guidelines.

```mermaid
graph TD
    CPD[CREATIVE PHASE DOCUMENTATION] --> Entry[ENTERING CREATIVE PHASE: [TYPE]]
    Entry --> Desc[Component Description]
    Desc --> Req[Requirements & Constraints]
    Req --> Options[Multiple Options]
    Options --> Analysis[Options Analysis]
    Analysis --> Recommend[Recommended Approach]
    Recommend --> Impl[Implementation Guidelines]
    Impl --> Verify[Verification]
    Verify --> Exit[EXITING CREATIVE PHASE]
```

## VERIFICATION

```mermaid
graph TD
    V[VERIFICATION CHECKLIST] --> C[All flagged components addressed?]
    V --> O[Multiple options explored?]
    V --> A[Pros and cons analyzed?]
    V --> R[Recommendations justified?]
    V --> I[Implementation guidelines provided?]
    V --> D[Design decisions documented?]
    C & O & A & R & I & D --> Decision{All Verified?}
    Decision -->|Yes| Complete[Ready for IMPLEMENT mode]
    Decision -->|No| Fix[Complete missing items]
```

Before completing, verify all components are addressed with options, analysis, justified recommendations, and guidelines. Update `tasks.md` and prepare for implementation.
