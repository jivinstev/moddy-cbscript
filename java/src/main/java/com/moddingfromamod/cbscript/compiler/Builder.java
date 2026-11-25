// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.compiler;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import com.moddingfromamod.cbscript.compiler.Ast.Block;
import com.moddingfromamod.cbscript.compiler.Ast.Coords;
import com.moddingfromamod.cbscript.compiler.Ast.Expr;
import com.moddingfromamod.cbscript.compiler.Ast.Value;
import com.moddingfromamod.cbscript.compiler.Ast.Var;
import com.moddingfromamod.cbscript.parse.Actions;
import com.moddingfromamod.cbscript.py.Py;
import com.moddingfromamod.cbscript.py.PyInt;

/** The Python constructors scriptparse.py's actions call, by name. */
final class Builder implements Actions.Factory {
    @SuppressWarnings("unchecked")
    private static <T> List<T> l(Object o) {
        return (List<T>) o;
    }

    private static String s(Object o) {
        return (String) o;
    }

    @Override
    public Object build(String what, int line, Object... a) {
        switch (what) {
            case "int":
                return Py.toInt(Py.of(s(a[0])));
            case "neg_int_str":
                return Py.neg(Py.toInt(Py.of(s(a[0])))).str();
            case "neg_float_str":
                return Py.neg(Py.toFloat(Py.of(s(a[0])))).str();
            case "uuid": {
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < 5; i++) {
                    if (i > 0) {
                        b.append('-');
                    }
                    b.append(((PyInt) Py.toInt(Py.of(s(a[i])))).value().toString(16).toUpperCase(java.util.Locale.ROOT));
                }
                return b.toString();
            }
            // sections
            case "reset_section":
                return new Blocks.ResetSection(line, l(a[0]));
            case "clock_section":
                return new Blocks.ClockSection(line, s(a[0]), l(a[1]));
            case "function_section":
                return new Blocks.FunctionSection(line, s(a[0]), l(a[1]), l(a[2]));
            case "template_function_section":
                return new Blocks.TemplateFunctionSection(line, s(a[0]), l(a[1]), l(a[2]), l(a[3]));
            case "macro_section":
                return new Blocks.MacroSection(line, s(a[0]), l(a[1]), l(a[2]));
            // with / calls
            case "with_items": {
                List<Object[]> items = new ArrayList<>();
                for (Object o : l(a[0])) {
                    items.add((Object[]) o);
                }
                return new Blocks.WithItems(line, items);
            }
            case "with_anonymous_block":
                return new Blocks.WithAnonymousBlock(line, (Blocks.WithItems) a[0], l(a[1]));
            case "function_call_block":
                return new Blocks.FunctionCallBlock(line, s(a[0]), l(a[1]), (Blocks.WithItems) a[2]);
            case "method_call_block":
                return new Blocks.MethodCallBlock(line, s(a[0]), s(a[1]), l(a[2]), (Blocks.WithItems) a[3]);
            case "template_function_call_block":
                return new Blocks.TemplateFunctionCallBlock(line, s(a[0]), l(a[1]), l(a[2]), (Blocks.WithItems) a[3]);
            case "set_with_macro_items": {
                Blocks.WithItems w = (Blocks.WithItems) a[1];
                if (a[0] instanceof Blocks.FunctionCallBlock c) {
                    c.withMacroItems = w;
                } else if (a[0] instanceof Blocks.MethodCallBlock c) {
                    c.withMacroItems = w;
                } else {
                    ((Blocks.TemplateFunctionCallBlock) a[0]).withMacroItems = w;
                }
                return null;
            }
            case "macro_call_block":
                return new Blocks.MacroCallBlock(line, s(a[0]), l(a[1]));
            case "import_block":
                return new Blocks.ImportBlock(line, s(a[0]));
            case "python_import_block":
                return new Blocks.PythonImportBlock(line, s(a[0]));
            // variables
            case "scoreboard_var":
                return new Ast.ScoreboardVar(s(a[0]), s(a[1]));
            case "scale_var":
                return new Ast.ScaleVar();
            case "array_const_var":
                return new Ast.ArrayConstVar(s(a[0]), s(a[1]), s(a[2]));
            case "array_expr_var":
                return new Ast.ArrayExprVar(s(a[0]), s(a[1]), (Expr) a[2]);
            case "block_path_var":
                return new Ast.BlockPathVar(s(a[0]), s(a[1]), (Coords) a[2], l(a[3]));
            case "virtualint_var":
                return new Ast.VirtualIntVar(s(a[0]));
            case "virtualint_var_int":
                return new Ast.VirtualIntVar(Py.of((Integer) a[0]));
            case "selector_id_var":
                return new Ast.SelectorIdVar(s(a[0]));
            case "command_var":
                return new Ast.CommandVar(s(a[0]), s(a[1]));
            case "storage_path_var":
                return new Ast.StoragePathVar((String) a[0], s(a[1]));
            // blocks
            case "comment_block":
                if (!s(a[0]).startsWith("#")) {
                    throw new com.moddingfromamod.cbscript.py.PyException("Exception", "Comment does not begin with \"#\"");
                }
                return new Blocks.CommentBlock(line, s(a[0]));
            case "command_block":
                return new Blocks.CommandBlock(line, s(a[0]));
            case "move_block":
                return new Blocks.MoveBlock(line, s(a[0]), (Coords) a[1]);
            case "python_for_block":
                return new Blocks.PythonForBlock(line, l(a[0]), (Value) a[1], l(a[2]));
            case "print_block":
                return new Blocks.PrintBlock(line, (Value) a[0]);
            case "python_if_block":
                return new Blocks.PythonIfBlock(line, (Value) a[0], l(a[1]), l(a[2]));
            case "python_if_block_else_list": {
                List<Block> elseSub = new ArrayList<>();
                for (Object ignored : l(a[2])) {
                    elseSub.add(new Blocks.TupleBlock());
                }
                return new Blocks.PythonIfBlock(line, (Value) a[0], l(a[1]), elseSub);
            }
            case "item":
                return new Ast.ExecItem(s(a[0]), a[1]);
            case "execute_block": {
                List<Ast.ExecItem> items = l(a[0]);
                List<Blocks.ElseItem> elses = new ArrayList<>();
                for (Object o : l(a[2])) {
                    Object[] t = (Object[]) o;
                    elses.add(new Blocks.ElseItem(l(t[0]), l(t[1])));
                }
                return new Blocks.ExecuteBlock(line, items, l(a[1]), elses);
            }
            case "while_block":
                return new Blocks.WhileBlock(line, l(a[0]), l(a[1]));
            case "for_selector_block":
                return new Blocks.ForSelectorBlock(line, s(a[0]), s(a[1]), l(a[2]));
            case "condition":
                return new Ast.Condition(s(a[0]), a[1]);
            case "score_condition":
                return new Ast.ScoreCondition((Expr) a[0], s(a[1]), (Expr) a[2]);
            case "block_condition":
                return new Ast.BlockCondition((Coords) a[0], s(a[1]));
            case "relcoords":
                return new Ast.RelCoords();
            case "relcoords3":
                return new Ast.RelCoords((Coords) a[0], (Coords) a[1], (Coords) a[2]);
            case "relcoord":
                return new Ast.RelCoord(s(a[0]), (Value) a[1]);
            case "relcoord_vector":
                return new Ast.RelCoordVector(line, s(a[0]), (Value) a[1]);
            case "const_string":
                return new Ast.ConstString(s(a[0]));
            case "const_number0":
                return Ast.ConstNumber.of(Py.of(0));
            case "interpreted_python":
                return new Ast.InterpretedPython(s(a[0]), line);
            case "for_index_block":
                return new Blocks.ForIndexBlock(line, (Var) a[0], (Expr) a[1], (Expr) a[2], (Expr) a[3], l(a[4]));
            case "switch_block": {
                List<Blocks.SwitchCase> cases = l(a[1]);
                return new Blocks.SwitchBlock(line, (Expr) a[0], cases);
            }
            case "switch_case":
                return new Blocks.SwitchCase(s(a[0]), (Object[]) a[1], line);
            case "block_switch_block":
                return new Blocks.BlockSwitchBlock(line, (Coords) a[0], l(a[1]), (Boolean) a[2]);
            case "block_id_switch_block":
                return new Blocks.BlockIdSwitchBlock(line, (Expr) a[0], l(a[1]), (Boolean) a[2]);
            case "block_case":
                return new Ast.BlockCase((String) a[0], a[1] == null ? null : l(a[1]), l(a[2]), (Boolean) a[3]);
            case "tell_block":
                return new Blocks.TellBlock(line, s(a[0]), s(a[1]));
            case "title_block": {
                Value[] times = null;
                if (a[2] != null) {
                    Object[] t = (Object[]) a[2];
                    times = new Value[] {(Value) t[0], (Value) t[1], (Value) t[2]};
                }
                return new Blocks.TitleBlock(line, s(a[0]), s(a[1]), times, s(a[3]));
            }
            case "push_block":
                return new Ast.PushBlock(line, l(a[0]));
            case "pop_block":
                return new Ast.PopBlock(line, l(a[0]));
            case "block_tag_block":
                return new Blocks.BlockTagBlock(line, s(a[0]), l(a[1]));
            case "entity_tag_block":
                return new Blocks.EntityTagBlock(line, s(a[0]), l(a[1]));
            case "item_tag_block":
                return new Blocks.ItemTagBlock(line, s(a[0]), l(a[1]));
            case "pointer_decl_block":
                return new Blocks.PointerDeclBlock(line, s(a[0]), s(a[1]));
            case "block_definition_block":
                return new Ast.BlockDefinition(line, s(a[0]), l(a[1]), (Coords) a[2]);
            case "number_macro_path":
                return new Ast.NumberMacroPath(s(a[0]), l(a[1]), s(a[2]), s(a[3]), (Value) a[4]);
            case "selector_assignment_block":
                return new Blocks.SelectorAssignmentBlock(line, s(a[0]), s(a[1]));
            case "selector_definition_block":
                return new Blocks.SelectorDefinitionBlock(line, s(a[0]), s(a[1]), (String) a[2], l(a[3]));
            case "selector_item":
                return new Blocks.SelectorItem(s(a[0]), (Object[]) a[1]);
            case "set_selector_based":
                ((Blocks.ArrayDefinitionBlock) a[0]).selectorBased = true;
                return null;
            case "array_definition_block":
                return new Blocks.ArrayDefinitionBlock(line, s(a[0]), (Value) a[1], (Value) a[2], (Boolean) a[3]);
            case "create_block":
                return new Ast.CreateBlock(line, s(a[0]), (Coords) a[1], (Value) a[2]);
            case "define_name_block":
                return new Blocks.DefineNameBlock(line, s(a[0]), s(a[1]));
            case "python_assignment_block":
                return new Blocks.PythonAssignmentBlock(line, s(a[0]), (Value) a[1]);
            case "python_tuple_assignment_block":
                return new Blocks.PythonTupleAssignmentBlock(line, l(a[0]), (Value) a[1]);
            case "nbt_data_block":
                return new Blocks.NbtDataBlock(line, (Ast.NbtPath) a[0], s(a[1]), (Ast.NbtSource) a[2]);
            case "entity_nbt_path":
                return new Ast.EntityNbtPath(s(a[0]), s(a[1]));
            case "storage_nbt_path":
                return new Ast.StorageNbtPath((String) a[0], s(a[1]));
            case "block_nbt_path":
                return new Ast.BlockNbtPath((Coords) a[0], s(a[1]));
            case "nbt_json":
                return new Ast.NbtJson(s(a[0]));
            case "nbt_remove_block":
                return new Blocks.NbtRemoveBlock(line, (Ast.NbtPath) a[0]);
            case "scoreboard_assignment_block":
                return new Blocks.ScoreboardAssignmentBlock(line, (Var) a[0], s(a[1]), (Expr) a[2]);
            case "create_expr":
                return new Ast.CreateExpr((Ast.CreateBlock) a[0]);
            case "vector_assignment_block":
                return new Blocks.VectorAssignmentBlock(line, (Ast.VectorVar) a[0], s(a[1]), a[2], false);
            case "vector_assignment_scalar_block":
                return new Blocks.VectorAssignmentBlock(line, (Ast.VectorVar) a[0], s(a[1]), a[2], true);
            case "vector_var":
                return new Ast.VectorVar(s(a[0]), a[1]);
            case "binop_expr":
                return new Ast.BinopExpr((Expr) a[0], s(a[1]), (Expr) a[2]);
            case "dot_expr":
                return new Ast.DotExpr((Ast.VectorExpr) a[0], (Ast.VectorExpr) a[1]);
            case "func_expr":
                return new Ast.FuncExpr((Blocks.FunctionCallBlock) a[0]);
            case "method_expr":
                return new Ast.MethodExpr((Blocks.MethodCallBlock) a[0]);
            case "unary_expr":
                return new Ast.UnaryExpr(s(a[0]), (Expr) a[1]);
            case "vector_expr":
                return new Ast.VectorTriple(new Expr[] {(Expr) a[0], (Expr) a[1], (Expr) a[2]});
            case "vector_var_expr":
                return new Ast.VectorVarExpr(s(a[0]));
            case "vector_var_const_vector":
                return new Ast.VectorVarConstVector((Value) a[0]);
            case "sel_vector_var_expr":
                return new Ast.SelVectorVarExpr(s(a[0]), s(a[1]));
            case "vector_binop_vector_expr":
                return new Ast.VectorBinop((Ast.VectorExpr) a[0], s(a[1]), a[2], false);
            case "vector_binop_scalar_expr":
                return new Ast.VectorBinop((Ast.VectorExpr) a[0], s(a[1]), a[2], true);
            case "vector_here_expr":
                return new Ast.VectorHereExpr((Value) a[0]);
            case "shaped_recipe_block": {
                List<String[]> keys = l(a[1]);
                return new Blocks.ShapedRecipeBlock(line, l(a[0]), keys, (Value) a[2], s(a[3]));
            }
            case "advancement_definition_block":
                return new Blocks.AdvancementDefinition(line, s(a[0]), s(a[1]));
            case "loot_table_definition_block":
                return new Blocks.LootTableDefinition(line, s(a[0]), s(a[1]), s(a[2]));
            case "predicate_definition_block":
                return new Blocks.PredicateDefinition(line, s(a[0]), s(a[1]));
            case "item_modifier_definition_block":
                return new Blocks.ItemModifierDefinition(line, s(a[0]), s(a[1]));
            default:
                throw new IllegalStateException("no builder for " + what);
        }
    }

    static BigInteger big(long v) {
        return BigInteger.valueOf(v);
    }
}
